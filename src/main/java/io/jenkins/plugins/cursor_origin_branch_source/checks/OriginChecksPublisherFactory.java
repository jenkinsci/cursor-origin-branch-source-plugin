package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.hm.hafner.util.FilteredLog;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.api.ChecksPublisherFactory;
import io.jenkins.plugins.util.PluginLogger;
import java.util.Optional;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;

/**
 * Provides a {@link ChecksPublisher} for jobs backed by a Cursor Origin SCM source.
 *
 * <p>The checks API asks every registered factory in turn and uses the first one that claims the job,
 * so declining a job that is not Origin backed is part of the contract rather than an error.
 *
 * <p>Only the {@link Run} overload is implemented. The {@link Job} one, which the checks API uses to
 * report a build as queued before it starts, is deliberately left to return nothing: the commit a
 * check must be reported against only becomes known once the build records it, so there is nothing
 * truthful to report that early.
 */
@Extension
public class OriginChecksPublisherFactory extends ChecksPublisherFactory {

    private final OriginSCMFacade scmFacade;
    private final DisplayURLProvider urlProvider;

    public OriginChecksPublisherFactory() {
        this(new OriginSCMFacade(), DisplayURLProvider.get());
    }

    OriginChecksPublisherFactory(OriginSCMFacade scmFacade, DisplayURLProvider urlProvider) {
        this.scmFacade = scmFacade;
        this.urlProvider = urlProvider;
    }

    @Override
    protected Optional<ChecksPublisher> createPublisher(final Run<?, ?> run, final TaskListener listener) {
        OriginChecksContext context = OriginChecksContext.fromRun(run, urlProvider.getRunURL(run), scmFacade);
        FilteredLog causeLogger = new FilteredLog("Causes for no suitable checks publisher found: ");
        PluginLogger consoleLogger = new PluginLogger(listener.getLogger(), "Cursor Origin Checks");
        if (context.isValid(causeLogger)) {
            return Optional.of(new OriginChecksPublisher(context, consoleLogger));
        }
        // Declining a job that is not Origin backed is the contract, so there is nothing to explain; for
        // a job that is, always say why its checks could not be published.
        if (scmFacade.findOriginSCMSource(run.getParent()).isPresent()) {
            consoleLogger.logEachLine(causeLogger.getErrorMessages());
        }
        return Optional.empty();
    }
}
