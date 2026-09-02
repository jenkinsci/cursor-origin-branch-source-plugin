package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.api.mixin.ChangeRequestSCMHead2;

public class OriginPullRequestSCMHead extends SCMHead implements ChangeRequestSCMHead2 {

    private static final long serialVersionUID = 1L;

    /** PR number as returned by the API (protobuf int64 encoded as string). */
    private final String number;

    /** The source branch name inside the same repo. */
    private final String headBranch;

    private final SCMHead target;

    public OriginPullRequestSCMHead(@NonNull String number, @NonNull String headBranch, @NonNull String baseBranch) {
        super("PR-" + number);
        this.number = number;
        this.headBranch = headBranch;
        this.target = new SCMHead(baseBranch);
    }

    public String getNumber() {
        return number;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    @NonNull
    @Override
    public String getId() {
        return number;
    }

    @NonNull
    @Override
    public SCMHead getTarget() {
        return target;
    }

    @NonNull
    @Override
    public String getOriginName() {
        return headBranch;
    }

    @NonNull
    @Override
    public ChangeRequestCheckoutStrategy getCheckoutStrategy() {
        return ChangeRequestCheckoutStrategy.HEAD;
    }
}
