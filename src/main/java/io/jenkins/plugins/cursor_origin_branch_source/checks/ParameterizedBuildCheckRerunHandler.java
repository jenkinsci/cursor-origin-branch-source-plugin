package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.ParameterizedJobMixIn.ParameterizedJob;
import jenkins.scm.api.SCMRevisionAction;

/**
 * Rerun handler for {@code ParameterizedBuild}s, that copies the previous build's {@code SCMRevisionAction}.
 */
@Extension
public class ParameterizedBuildCheckRerunHandler extends OriginCheckRerunHandler {

    private static final Logger LOGGER = Logger.getLogger(ParameterizedBuildCheckRerunHandler.class.getName());

    // copied from ReplayAction
    private static final List<Class<? extends Action>> COPIED_ACTIONS =
            List.of(ParametersAction.class, SCMRevisionAction.class);

    @Override
    public boolean canRerun(@NonNull Run<?, ?> run) {
        return run.getParent() instanceof ParameterizedJob;
    }

    @Override
    public boolean scheduleRerun(@NonNull Run<?, ?> run, @NonNull OriginCheckRerunCause cause) {
        // common run checks are completed before we are called
        // e.g. is the project buildable and using the right SCM Source

        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(cause));
        for (Class<? extends Action> c : COPIED_ACTIONS) {
            actions.addAll(run.getActions(c));
        }
        Queue.Item qi = ParameterizedJobMixIn.scheduleBuild2(run.getParent(), 0, actions.toArray(new Action[0]));
        if (qi == null) {
            LOGGER.info(() ->
                    "check_run.rerequested: replay scheduling returned no queue item for " + run.getExternalizableId());
            return false;
        }
        return true;
    }
}
