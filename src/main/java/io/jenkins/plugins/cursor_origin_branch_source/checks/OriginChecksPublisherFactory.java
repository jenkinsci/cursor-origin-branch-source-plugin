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
        return createPublisher(
                run.getParent(), listener, OriginChecksContext.fromRun(run, urlProvider.getRunURL(run), scmFacade));
    }

    @Override
    protected Optional<ChecksPublisher> createPublisher(final Job<?, ?> job, final TaskListener listener) {
        return createPublisher(job, listener, OriginChecksContext.fromJob(job, urlProvider.getJobURL(job), scmFacade));
    }

    private Optional<ChecksPublisher> createPublisher(
            final Job<?, ?> job, final TaskListener listener, final OriginChecksContext context) {
        FilteredLog causeLogger = new FilteredLog("Causes for no suitable checks publisher found: ");
        PluginLogger consoleLogger = new PluginLogger(listener.getLogger(), "Cursor Origin Checks");
        if (context.isValid(causeLogger)) {
            return Optional.of(new OriginChecksPublisher(context, consoleLogger));
        }
        if (OriginChecksConfigurations.forJob(scmFacade, job).isVerboseConsoleLog()) {
            consoleLogger.logEachLine(causeLogger.getErrorMessages());
        }
        return Optional.empty();
    }
}
