package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;

/**
 * Records what has already been reported for one check run of a build.
 *
 * <p>Check-run upserts are idempotent, but annotations are appended, not replaced: Origin has no way
 * to tell a retry from a genuine second batch. Remembering how many annotations were already accepted
 * lets a repeated publish of the same check send only the ones Origin has not seen.
 */
class OriginChecksAction extends InvisibleAction {

    private final String checkKey;
    private final String checkRunId;
    private int publishedAnnotations;

    OriginChecksAction(@NonNull String checkKey, @NonNull String checkRunId) {
        this.checkKey = checkKey;
        this.checkRunId = checkRunId;
    }

    @NonNull
    String getCheckKey() {
        return checkKey;
    }

    /** The server assigned check-run id ({@code cr_…}), which annotations are addressed by. */
    @NonNull
    String getCheckRunId() {
        return checkRunId;
    }

    int getPublishedAnnotations() {
        return publishedAnnotations;
    }

    void addPublishedAnnotations(int count) {
        publishedAnnotations += count;
    }
}
