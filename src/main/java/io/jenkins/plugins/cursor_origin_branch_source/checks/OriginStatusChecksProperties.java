package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.Extension;
import hudson.model.Job;
import io.jenkins.plugins.checks.status.AbstractStatusChecksProperties;

/**
 * Drives the checks API's automatic build status check for Cursor Origin backed jobs.
 *
 * <p>Without this extension the checks API publishes nothing on its own: only explicit
 * {@code publishChecks} and {@code withChecks} calls from a pipeline would reach the publisher.
 */
@Extension
public class OriginStatusChecksProperties extends AbstractStatusChecksProperties {

    /**
     * Name of the check that reports the overall build status. Origin matches required checks on it, so
     * it is fixed rather than configurable: changing it would silently break the required-check
     * configuration of every repository already reporting under the old name.
     */
    static final String CHECK_NAME = "Jenkins";

    private final OriginSCMFacade scmFacade;

    public OriginStatusChecksProperties() {
        this(new OriginSCMFacade());
    }

    OriginStatusChecksProperties(OriginSCMFacade scmFacade) {
        this.scmFacade = scmFacade;
    }

    @Override
    public boolean isApplicable(final Job<?, ?> job) {
        return scmFacade.findOriginSCMSource(job).isPresent();
    }

    @Override
    public String getName(final Job<?, ?> job) {
        return CHECK_NAME;
    }

    @Override
    public boolean isSkipped(final Job<?, ?> job) {
        return getConfigurations(job).isSkip();
    }

    private OriginChecksConfigurations getConfigurations(final Job<?, ?> job) {
        return OriginChecksConfigurations.forJob(scmFacade, job);
    }
}
