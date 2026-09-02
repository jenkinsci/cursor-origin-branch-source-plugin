package io.jenkins.plugins.cursor_origin_branch_source;

import hudson.Extension;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/** Trait that enables branch discovery for an {@link OriginSCMSource}. */
public class BranchDiscoveryTrait extends SCMSourceTrait {

    @DataBoundConstructor
    public BranchDiscoveryTrait() {}

    @Override
    protected void decorateContext(SCMSourceContext<?, ?> context) {
        ((OriginSCMSourceContext) context).wantBranches(true);
    }

    @Override
    public boolean includeCategory(SCMHeadCategory category) {
        return category instanceof UncategorizedSCMHeadCategory;
    }

    @Extension
    @Symbol("originBranchDiscovery")
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        @Override
        public String getDisplayName() {
            return "Discover branches";
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
