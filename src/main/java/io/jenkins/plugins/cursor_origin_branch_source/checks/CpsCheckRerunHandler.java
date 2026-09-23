package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.CauseAction;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.Collections;
import java.util.logging.Logger;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;

/**
 * Rerun handler for workflow-cps pipelines, using the built-in replay mechanism.
 */
@OptionalExtension(requirePlugins = "workflow-cps")
public class CpsCheckRerunHandler extends OriginCheckRerunHandler {

    private static final Logger LOGGER = Logger.getLogger(CpsCheckRerunHandler.class.getName());

    @Override
    public boolean isRerunnable(@NonNull Run<?, ?> run) {
        return run.getAction(ReplayAction.class) != null;
    }

    @Override
    public boolean scheduleRerun(@NonNull Run<?, ?> run, @NonNull OriginCheckRerunCause cause) {
        ReplayAction action = run.getAction(ReplayAction.class);
        if (action == null) {
            return false;
        }
        Queue.Item qi = action.run2(action.getOriginalScript(), action.getOriginalLoadedScripts(), true);
        if (qi == null) {
            LOGGER.info(() -> "check_run.rerequested: replay scheduling returned no queue item for "
                    + run.getExternalizableId());
            return false;
        }
        CauseAction causeAction = new CauseAction(cause);
        causeAction.foldIntoExisting(qi, qi.task, Collections.emptyList());
        return true;
    }
}
