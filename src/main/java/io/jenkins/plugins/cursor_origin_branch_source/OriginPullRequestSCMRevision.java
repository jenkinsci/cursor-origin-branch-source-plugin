package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.mixin.ChangeRequestSCMRevision;

public class OriginPullRequestSCMRevision extends ChangeRequestSCMRevision<OriginPullRequestSCMHead> {

    private static final long serialVersionUID = 1L;

    private final String headHash;

    public OriginPullRequestSCMRevision(
            @NonNull OriginPullRequestSCMHead head, @NonNull String headHash, @NonNull String baseHash) {
        super(head, new AbstractGitSCMSource.SCMRevisionImpl(head.getTarget(), baseHash));
        this.headHash = headHash;
    }

    public String getHeadHash() {
        return headHash;
    }

    @Override
    public boolean equivalent(@NonNull ChangeRequestSCMRevision<?> revision) {
        return revision instanceof OriginPullRequestSCMRevision other && headHash.equals(other.headHash);
    }

    @Override
    protected int _hashCode() {
        return headHash.hashCode();
    }

    @Override
    public String toString() {
        return headHash;
    }
}
