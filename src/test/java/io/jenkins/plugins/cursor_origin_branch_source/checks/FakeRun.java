package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.model.Run;

/**
 * A {@link Run} of a {@link FakeJob} that needs no running Jenkins.
 *
 * <p>The two-argument {@code Run} constructor only records the job, the timestamp and the state, so
 * nothing here touches the file system or the singleton. That is enough for the checks code, which
 * reads the build's parent, its externalizable id and the actions attached to it — and since actions
 * are held by {@code Actionable} itself, adding and reading them works for real rather than being
 * stubbed.
 */
class FakeRun extends Run<FakeJob, FakeRun> {

    private static final long FIXED_TIMESTAMP = 1_733_000_000_000L;

    FakeRun(FakeJob job, int number) {
        super(job, FIXED_TIMESTAMP);
        this.number = number;
    }
}
