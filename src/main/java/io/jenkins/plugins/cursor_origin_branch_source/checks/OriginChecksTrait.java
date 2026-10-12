package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.Extension;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSourceContext;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Trait that configures how build status is reported to Cursor Origin as a check run.
 *
 * <p>Checks are published without this trait too, so the trait only overrides the defaults — which
 * currently means the one thing it can do is stop them being published. The display name is
 * deliberately neutral rather than promising to report, both because reporting happens either way
 * and so that further options can be added here without renaming it.
 */
public class OriginChecksTrait extends SCMSourceTrait implements OriginChecksConfigurations {

    private boolean skip;

    @DataBoundConstructor
    public OriginChecksTrait() {}

    @Override
    public boolean isSkip() {
        return skip;
    }

    @DataBoundSetter
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    @Extension
    @Symbol("originChecks")
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        @Override
        public String getDisplayName() {
            return "Cursor Origin checks configuration";
        }

        @Override
        public Class<? extends SCMSourceContext> getContextClass() {
            return OriginSCMSourceContext.class;
        }

        @Override
        public Class<? extends SCMSource> getSourceClass() {
            return OriginSCMSource.class;
        }
    }
}
