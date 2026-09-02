package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import jenkins.plugins.git.GitSCMSourceContext;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;

public class OriginSCMSourceContext extends GitSCMSourceContext<OriginSCMSourceContext, OriginSCMSourceRequest> {

    private boolean wantPRs;

    public OriginSCMSourceContext(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer) {
        super(criteria, observer);
    }

    public boolean wantPRs() {
        return wantPRs;
    }

    public OriginSCMSourceContext wantPRs(boolean include) {
        wantPRs = wantPRs || include;
        return this;
    }

    @NonNull
    @Override
    public OriginSCMSourceRequest newRequest(@NonNull SCMSource source, @CheckForNull TaskListener listener) {
        return new OriginSCMSourceRequest(source, this, listener);
    }
}
