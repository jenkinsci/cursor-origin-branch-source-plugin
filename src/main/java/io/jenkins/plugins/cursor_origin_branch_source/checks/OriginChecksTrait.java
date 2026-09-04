package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.Extension;
import hudson.util.FormValidation;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSourceContext;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Trait that configures how build status is reported to Cursor Origin as a check run.
 *
 * <p>Checks are published without this trait too; adding it only overrides the defaults. Note that the
 * check name is part of what Origin matches required checks on, so changing it on a repository with
 * required checks configured will require that configuration to be updated as well.
 */
public class OriginChecksTrait extends SCMSourceTrait implements OriginChecksConfigurations {

    private String name = "Jenkins";
    private boolean skip;
    private boolean unstableBuildNeutral;
    private boolean suppressLogs;
    private boolean skipProgressUpdates;
    private boolean verboseConsoleLog;

    @DataBoundConstructor
    public OriginChecksTrait() {}

    @Override
    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean isSkip() {
        return skip;
    }

    @DataBoundSetter
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    @Override
    public boolean isUnstableBuildNeutral() {
        return unstableBuildNeutral;
    }

    @DataBoundSetter
    public void setUnstableBuildNeutral(boolean unstableBuildNeutral) {
        this.unstableBuildNeutral = unstableBuildNeutral;
    }

    @Override
    public boolean isSuppressLogs() {
        return suppressLogs;
    }

    @DataBoundSetter
    public void setSuppressLogs(boolean suppressLogs) {
        this.suppressLogs = suppressLogs;
    }

    @Override
    public boolean isSkipProgressUpdates() {
        return skipProgressUpdates;
    }

    @DataBoundSetter
    public void setSkipProgressUpdates(boolean skipProgressUpdates) {
        this.skipProgressUpdates = skipProgressUpdates;
    }

    @Override
    public boolean isVerboseConsoleLog() {
        return verboseConsoleLog;
    }

    @DataBoundSetter
    public void setVerboseConsoleLog(boolean verboseConsoleLog) {
        this.verboseConsoleLog = verboseConsoleLog;
    }

    @Extension
    @Symbol("originChecks")
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        @Override
        public String getDisplayName() {
            return "Report build status to Cursor Origin as a check";
        }

        @Override
        public Class<? extends SCMSourceContext> getContextClass() {
            return OriginSCMSourceContext.class;
        }

        @Override
        public Class<? extends SCMSource> getSourceClass() {
            return OriginSCMSource.class;
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (name == null || name.isBlank()) {
                return FormValidation.error("Name should not be empty!");
            }
            return FormValidation.ok();
        }
    }
}
