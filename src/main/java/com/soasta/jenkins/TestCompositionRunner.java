/*
 * Copyright (c) 2012-2013, CloudBees, Inc., SOASTA, Inc.
 * All Rights Reserved.
 */
package com.soasta.jenkins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildListener;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.util.ArgumentListBuilder;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.QuotedStringTokenizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;


/**
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("deprecation")
public class TestCompositionRunner extends AbstractSCommandBuilder {
    /**
     * Composition to execute.
     */
    private final String composition;
    private boolean deleteOldResults;
    private int maxDaysOfResults;
    private String additionalOptions;
    private List<TransactionThreshold> thresholds;
    private DeleteOldResultsSettings deleteOldResultsSettings;
    private boolean generatePlotCSV;
     
    @DataBoundConstructor
    public TestCompositionRunner(String cloudTestServerID, String composition) {
        super(cloudTestServerID);
        this.composition = composition;
    }
    
    public List<TransactionThreshold> getThresholds() {
        return thresholds;
    }
    
    @DataBoundSetter
    public void setThresholds(List<TransactionThreshold> thresholds)
    {
      this.thresholds = thresholds;
    }
    
    public String getComposition() {
        return composition;
    }

    public boolean getDeleteOldResults() {
        return deleteOldResults;
    }

    public int getMaxDaysOfResults() {
        return maxDaysOfResults;
    }

    public String getAdditionalOptions() {
        return Util.fixEmptyAndTrim(additionalOptions);
    }
    
    @DataBoundSetter
    public void setAdditionalOptions(String additionalOptions)
    {
      this.additionalOptions = additionalOptions;
    }

    public boolean getGeneratePlotCSV() {
        return generatePlotCSV;
    }
    
    @DataBoundSetter
    public void setGeneratePlotCSV(boolean generatePlotCSV)
    {
      this.generatePlotCSV = generatePlotCSV;
    }
    
    @DataBoundSetter
    public final void setDeleteOldResultsSettings(DeleteOldResultsSettings deleteOldResultsSettings)
    {
      this.deleteOldResultsSettings = deleteOldResultsSettings;
      this.deleteOldResults = (deleteOldResultsSettings != null);
      this.maxDaysOfResults = (deleteOldResultsSettings == null ? 0 : deleteOldResultsSettings.maxDaysOfResults);
    }
    
    public DeleteOldResultsSettings getDeleteOldResultsSettings()
    {
      return deleteOldResultsSettings;
    }

    public Object readResolve() throws IOException {
        if (getCloudTestServerID() != null)
            return this;

        // We don't have a server ID.
        // This means the builder config is based an older version the plug-in.

        // Look up the server by URL instead.
        // We'll use the ID going forward.
        CloudTestServer s = CloudTestServer.getByURL(getUrl());

        LOGGER.info("Matched server URL " + getUrl() + " to ID: " + s.getId() + "; re-creating.");

        TestCompositionRunner result = new TestCompositionRunner(s.getId(), composition);
        if(deleteOldResults)
        {
          result.setDeleteOldResultsSettings(new DeleteOldResultsSettings(maxDaysOfResults));
        }
        result.setAdditionalOptions(additionalOptions);
        result.setThresholds(thresholds);
        result.setGeneratePlotCSV(generatePlotCSV);
        if(getUrl() != null)
        {
          result.setUrl(getUrl());
        }
        return result;
    }
    
    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException
    {
   // Create a unique sub-directory to store all test results.
      String resultsDir = "." + getClass().getName();
      
      // Split by newline.
      EnvVars envs = run.getEnvironment(listener);
      String[] compositions = envs.expand(this.composition).split("[\r\n]+");
      String additionalOptionsExpanded = additionalOptions == null ? 
          null : envs.expand(additionalOptions);
      String[] options = additionalOptionsExpanded == null ?
          null : new QuotedStringTokenizer(additionalOptionsExpanded).toArray();

      for (String composition : compositions) {
          ArgumentListBuilder args = getSCommandArgs(run, workspace, listener);

          args.add("cmd=play", "wait", "format=junitxml")
              .add("name=" + composition);
          
          // if thresholds are included in this post-build action, add them to scommand arguments 
          if (thresholds != null) {
              displayTransactionThreholds(listener.getLogger());
            
              for (TransactionThreshold threshold : thresholds) {
                  args.add("validation=" + threshold.toScommandString());
              }
          }
          
          String fileName = composition + ".xml";

          // Strip off any leading slash characters (composition names
          // will typically be the full CloudTest folder path).
          if (fileName.startsWith("/")) {
              fileName = fileName.substring(1);
          }

          // Put the file in the test results directory.
          fileName = resultsDir + File.separator + fileName;
          
          FilePath xml = new FilePath(workspace, fileName);
          
          // Make sure the directory exists.
          xml.getParent().mkdirs();

          // Add the additional options to the composition if there are any.
          if (options != null) {
              args.add(options);
          }

          if (generatePlotCSV) {
              args.add("outputthresholdcsvdir=" + workspace);
          }

          // Run it!
          launcher.launch()
              .cmds(args)
              .pwd(workspace)
              .stdout(xml.write())
              .stderr(listener.getLogger())
              .join();

          if (xml.length() == 0) {
              // SCommand did not produce any output.
              // This should never happen, but just in case...
              return;
          }

          if (deleteOldResults) {
              // Run SCommand again to clean up the old results.
              args = getSCommandArgs(run, workspace, listener);

              args.add("cmd=delete", "type=result")
                  .add("path=" + composition)
                  .add("maxage=" + maxDaysOfResults);

              launcher
                  .launch()
                  .cmds(args)
                  .pwd(workspace)
                  .stdout(listener)
                  .stderr(listener.getLogger())
                  .join();
          }
      }
      
      // Now that we've finished running all the compositions, pass
      // the results directory off to the JUnit archiver.
      String resultsPattern = resultsDir + "/**/*.xml";
      JUnitResultArchiver archiver = new JUnitResultArchiver(
          resultsPattern,
          true,
          new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(
              Saveable.NOOP,
              Collections.singleton(new JunitResultPublisher(null))));
      archiver.perform(run, workspace, launcher, listener);
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
      FilePath filePath = build.getWorkspace();
      if(filePath == null) {
          return false;
      }else {
          perform(build, filePath, launcher, listener);
          return true;
      }
    }
    
    private void displayTransactionThreholds(PrintStream jenkinsLogger) {
        String THRESHOLD_TABLE_FORMAT = "%-15s %-20s %7s";
        jenkinsLogger.println("~");
        jenkinsLogger.println("Custom Transaction Threholds:");
        for (TransactionThreshold threshold : thresholds) {
            String formattedString = String.format(THRESHOLD_TABLE_FORMAT,threshold.getTransactionname(),threshold.getThresholdname(),threshold.getThresholdvalue());
            jenkinsLogger.println(formattedString);
        }
        jenkinsLogger.println("~");
    }
       
    @Extension
    @Symbol("playComposition")
    public static class DescriptorImpl extends AbstractCloudTestBuilderDescriptor {
        @Override
        public String getDisplayName() {
            return "Play Composition(s)";
        }

        /**
         * Called automatically by Jenkins whenever the "composition"
         * field is modified by the user.
         * @param value the new composition name.
         */
        public FormValidation doCheckComposition(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Composition name is required.");
            } else {
                return FormValidation.ok();
            }
        }

        /**
         * Called automatically by Jenkins whenever the "maxDaysOfResults"
         * field is modified by the user.
         * @param value the new maximum age, in days.
         */
        public FormValidation doCheckMaxDaysOfResults(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Days to keep results is required.");
            } else {
                try {
                    int maxDays = Integer.parseInt(value);

                    if (maxDays <= 0) {
                        return FormValidation.error("Value must be > 0.");
                    } else {
                        return FormValidation.ok();
                    }
                } catch (NumberFormatException e) {
                    return FormValidation.error("Value must be numeric.");
                }
            }
        }

        public AutoCompletionCandidates doAutoCompleteComposition(@QueryParameter String cloudTestServerID) throws IOException, InterruptedException {
            CloudTestServer s = CloudTestServer.getByID(cloudTestServerID);

            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add(install(s))
                .add("list", "type=composition")
                .add("url=" + s.getUrl())
                .add("username=" + s.getUsername());
            
            if (s.getPassword() != null)
                args.addMasked("password=" + s.getPassword());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = new LocalLauncher(TaskListener.NULL).launch().cmds(args).stdout(out).join();
            if (exit==0) {
                BufferedReader r = new BufferedReader(new StringReader(out.toString()));
                AutoCompletionCandidates a = new AutoCompletionCandidates();
                String line;
                while ((line=r.readLine())!=null) {
                    if (line.endsWith("object(s) found."))  continue;
                    a.add(line);
                }
                return a;
            }
            return new AutoCompletionCandidates(); // no candidate
        }

        private synchronized FilePath install(CloudTestServer s) throws IOException, InterruptedException {
            SCommandInstaller sCommandInstaller = new SCommandInstaller(s);
            return sCommandInstaller.scommand(Jenkins.getInstance(), TaskListener.NULL);
        }
    }

    public static class DeleteOldResultsSettings {
        private final int maxDaysOfResults;

        @DataBoundConstructor
        public DeleteOldResultsSettings(int maxDaysOfResults) {
            this.maxDaysOfResults = maxDaysOfResults;
        }

        public int getMaxDaysOfResults() {
            return maxDaysOfResults;
        }
    }
    
    
    
    private static final Logger LOGGER = Logger.getLogger(TestCompositionRunner.class.getName());
}
