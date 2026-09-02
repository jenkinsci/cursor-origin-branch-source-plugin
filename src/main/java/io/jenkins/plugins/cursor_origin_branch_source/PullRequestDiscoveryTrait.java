package io.jenkins.plugins.cursor_origin_branch_source;

import hudson.Extension;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/** Trait that enables pull request discovery for an {@link OriginSCMSource}. */
public class PullRequestDiscoveryTrait extends SCMSourceTrait {

    @DataBoundConstructor
    public PullRequestDiscoveryTrait() {}

    @Override
    protected void decorateContext(SCMSourceContext<?, ?> context) {
        ((OriginSCMSourceContext) context).wantPRs(true);
    }

    @Override
    public boolean includeCategory(SCMHeadCategory category) {
        return category instanceof ChangeRequestSCMHeadCategory;
    }

    @Extension
    @Symbol("originPullRequestDiscovery")
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        @Override
        public String getDisplayName() {
            return "Discover pull requests";
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
