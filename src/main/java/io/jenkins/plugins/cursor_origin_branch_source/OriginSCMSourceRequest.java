package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.TaskListener;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import jenkins.plugins.git.GitSCMSourceRequest;
import jenkins.scm.api.SCMSource;

public class OriginSCMSourceRequest extends GitSCMSourceRequest {

    private final boolean fetchBranches;
    private final boolean fetchPRs;
    /** Head branch names of open PRs discovered during this request; populated lazily during retrieve. */
    private Set<String> prHeadBranches;

    OriginSCMSourceRequest(SCMSource source, OriginSCMSourceContext context, @CheckForNull TaskListener listener) {
        super(source, context, listener);
        fetchBranches = context.wantBranches();
        fetchPRs = context.wantPRs();
    }

    public boolean isFetchBranches() {
        return fetchBranches;
    }

    public boolean isFetchPRs() {
        return fetchPRs;
    }

    /** Records the head branch names of open PRs found during this request. */
    public void setPRHeadBranches(Set<String> branches) {
        this.prHeadBranches = new HashSet<>(branches);
    }

    /** Returns the head branch names of open PRs, or empty set if not yet populated. */
    public Set<String> getPRHeadBranches() {
        return prHeadBranches != null ? Collections.unmodifiableSet(prHeadBranches) : Collections.emptySet();
    }
}
