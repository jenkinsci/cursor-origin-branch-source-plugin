package io.jenkins.plugins.cursor_origin_branch_source.checks;

import hudson.model.Job;

/**
 * How a job wants its Cursor Origin checks reported.
 *
 * <p>Implemented by {@link OriginChecksTrait} so that the settings can be configured per SCM source,
 * and by {@link DefaultOriginChecksConfigurations} for jobs whose source has no such trait.
 */
interface OriginChecksConfigurations {

    /**
     * Resolves the settings for {@code job} from the {@link OriginChecksTrait} on its SCM source,
     * falling back to the defaults when the source carries no such trait.
     */
    static OriginChecksConfigurations forJob(OriginSCMFacade scmFacade, Job<?, ?> job) {
        return scmFacade
                .findOriginSCMSource(job)
                .flatMap(source -> source.getTraits().stream()
                        .filter(OriginChecksConfigurations.class::isInstance)
                        .map(OriginChecksConfigurations.class::cast)
                        .findFirst())
                .orElseGet(DefaultOriginChecksConfigurations::new);
    }

    /** Name of the check that reports the overall build status. */
    String getName();

    /** Whether to suppress the automatic build status check entirely. */
    boolean isSkip();

    /** Whether an unstable build is reported as neutral rather than as a failure. */
    boolean isUnstableBuildNeutral();

    /** Whether to leave the build log out of the reported check output. */
    boolean isSuppressLogs();

    /** Whether to report only the final status, skipping the queued, checkout and stage updates. */
    boolean isSkipProgressUpdates();

    /** Whether to explain in the build log why no checks could be published. */
    boolean isVerboseConsoleLog();

    /** The settings that apply when the SCM source has no {@link OriginChecksTrait}. */
    class DefaultOriginChecksConfigurations implements OriginChecksConfigurations {

        @Override
        public String getName() {
            return "Jenkins";
        }

        @Override
        public boolean isSkip() {
            return false;
        }

        @Override
        public boolean isUnstableBuildNeutral() {
            return false;
        }

        @Override
        public boolean isSuppressLogs() {
            return false;
        }

        @Override
        public boolean isSkipProgressUpdates() {
            return false;
        }

        @Override
        public boolean isVerboseConsoleLog() {
            return false;
        }
    }
}
