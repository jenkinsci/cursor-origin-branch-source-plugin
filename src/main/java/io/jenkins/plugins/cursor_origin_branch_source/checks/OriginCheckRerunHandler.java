package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;

/**
 * Extension point for strategies that can rerun a Jenkins build in response to a
 * {@code repository.check_run.rerequested} webhook from Cursor Origin.
 *
 * <p>Implementations advertise whether a given run is rerunnable and perform the scheduling. The
 * publisher queries all registered handlers to decide whether to set {@code isRerequestable} on a
 * check run, and the event subscriber delegates scheduling to the first handler that claims the run.
 */
public abstract class OriginCheckRerunHandler implements ExtensionPoint {

    /** Returns whether this handler can rerun {@code run}. */
    public abstract boolean isRerunnable(@NonNull Run<?, ?> run);

    /**
     * Schedules a rerun of {@code run} caused by {@code cause}.
     *
     * @return {@code true} if a queue item was created, {@code false} if scheduling failed
     */
    public abstract boolean scheduleRerun(@NonNull Run<?, ?> run, @NonNull OriginCheckRerunCause cause);

    /** All registered handlers in extension-point priority order. */
    public static ExtensionList<OriginCheckRerunHandler> all() {
        return ExtensionList.lookup(OriginCheckRerunHandler.class);
    }

    /**
     * Reruns {@code run} using the first handler that reports it as rerunnable.
     *
     * @return {@code true} if a handler accepted and scheduled the rerun
     */
    static boolean rerun(@NonNull Run<?, ?> run, @NonNull OriginCheckRerunCause cause) {
        return all().stream()
                .filter(h -> h.isRerunnable(run))
                .findFirst()
                .map(h -> h.scheduleRerun(run, cause))
                .orElse(false);
    }
}
