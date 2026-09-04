package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.hm.hafner.util.FilteredLog;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.CursorOriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import java.util.Optional;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSourceOwner;

/**
 * The Cursor Origin coordinates a check run is published with: which repository, which commit, which
 * credentials, and how the reported check relates to the Jenkins job that produced it.
 *
 * <p>A context is created from either a {@link Run} (once a build exists) or a {@link Job} (while the
 * build is still queued and there is no run yet). Instances are cheap to create but resolving the head
 * SHA for the job-only case performs a remote call, so create them lazily and validate them with
 * {@link #isValid(FilteredLog)} before calling any of the resolving getters.
 */
class OriginChecksContext {

    private final Job<?, ?> job;

    @CheckForNull
    private final Run<?, ?> run;

    private final String url;
    private final OriginSCMFacade scmFacade;

    @CheckForNull
    private final String sha;

    /** Creates a context for a build that has already started. */
    static OriginChecksContext fromRun(
            @NonNull Run<?, ?> run, @NonNull String runUrl, @NonNull OriginSCMFacade scmFacade) {
        return new OriginChecksContext(run.getParent(), run, runUrl, scmFacade);
    }

    /** Creates a context for a job whose build has not started yet, e.g. while it sits in the queue. */
    static OriginChecksContext fromJob(
            @NonNull Job<?, ?> job, @NonNull String jobUrl, @NonNull OriginSCMFacade scmFacade) {
        return new OriginChecksContext(job, null, jobUrl, scmFacade);
    }

    private OriginChecksContext(
            @NonNull Job<?, ?> job,
            @CheckForNull Run<?, ?> run,
            @NonNull String url,
            @NonNull OriginSCMFacade scmFacade) {
        this.job = job;
        this.run = run;
        this.url = url;
        this.scmFacade = scmFacade;
        this.sha = run != null ? resolveHeadSha(run) : resolveHeadSha(job);
    }

    /**
     * Reports whether checks can be published for this job, recording the reasons why not in
     * {@code logger} so that they can be surfaced to the user on request.
     */
    boolean isValid(@NonNull FilteredLog logger) {
        Optional<OriginSCMSource> source = resolveSource();
        if (source.isEmpty()) {
            logger.logError("Job does not use a Cursor Origin SCM source");
            return false;
        }
        String credentialsId = source.get().getCredentialsId();
        if (credentialsId == null || credentialsId.isBlank()) {
            logger.logError("No credentials configured on the Cursor Origin SCM source");
            return false;
        }
        if (scmFacade.findCredentials(job, credentialsId).isEmpty()) {
            logger.logError("No Cursor Origin app credentials found with id: '%s'", credentialsId);
            return false;
        }
        if (sha == null || sha.isBlank()) {
            logger.logError("No HEAD SHA found for %s/%s", getRepoOwner(), getRepository());
            return false;
        }
        return true;
    }

    /** The commit SHA the check run is reported against. */
    @NonNull
    String getHeadSha() {
        if (sha == null || sha.isBlank()) {
            throw new IllegalStateException("No SHA found for job: " + job.getFullName());
        }
        return sha;
    }

    @NonNull
    String getRepoOwner() {
        return resolveSource()
                .map(OriginSCMSource::getRepoOwner)
                .orElseThrow(() ->
                        new IllegalStateException("No Cursor Origin SCM source found for job: " + job.getFullName()));
    }

    @NonNull
    String getRepository() {
        return resolveSource()
                .map(OriginSCMSource::getRepository)
                .orElseThrow(() ->
                        new IllegalStateException("No Cursor Origin SCM source found for job: " + job.getFullName()));
    }

    /** The Jenkins URL that a check run without its own details URL links to. */
    @NonNull
    String getUrl() {
        return url;
    }

    @NonNull
    Job<?, ?> getJob() {
        return job;
    }

    Optional<Run<?, ?>> getRun() {
        return Optional.ofNullable(run);
    }

    /**
     * The check suite key, which identifies the logical group of checks this job reports across
     * builds. Required-check configuration in Origin is keyed on it, so it must stay stable and must
     * not encode a build number.
     */
    @NonNull
    String getSuiteKey() {
        return resolveOwner().map(SCMSourceOwner::getFullName).orElseGet(job::getFullName);
    }

    /** Human facing name of the check suite; display only, never used for matching. */
    @NonNull
    String getSuiteName() {
        return resolveOwner().map(SCMSourceOwner::getFullDisplayName).orElseGet(job::getFullDisplayName);
    }

    /**
     * Identifies this attempt at reporting the suite and its runs. A new value supersedes any
     * previous attempt for the same keys, so each Jenkins build gets its own.
     */
    @NonNull
    String getExternalId() {
        return run != null ? run.getExternalizableId() : job.getFullName();
    }

    /** Creates an Origin API client authenticated as the app the SCM source is configured with. */
    @NonNull
    OriginServiceApi createApi() {
        String credentialsId = resolveSource()
                .map(OriginSCMSource::getCredentialsId)
                .orElseThrow(() ->
                        new IllegalStateException("No Cursor Origin SCM source found for job: " + job.getFullName()));
        CursorOriginAppCredentials credentials = scmFacade
                .findCredentials(job, credentialsId)
                .orElseThrow(() ->
                        new IllegalStateException("No Cursor Origin app credentials found with id: " + credentialsId));
        return credentials.api();
    }

    private Optional<OriginSCMSource> resolveSource() {
        return scmFacade.findOriginSCMSource(job);
    }

    private Optional<SCMSourceOwner> resolveOwner() {
        return resolveSource().map(OriginSCMSource::getOwner);
    }

    @CheckForNull
    private String resolveHeadSha(@NonNull Run<?, ?> theRun) {
        return resolveSource()
                .flatMap(source -> scmFacade.findRevision(source, theRun))
                .flatMap(scmFacade::findHash)
                .orElse(null);
    }

    @CheckForNull
    private String resolveHeadSha(@NonNull Job<?, ?> theJob) {
        Optional<OriginSCMSource> source = resolveSource();
        Optional<SCMHead> head = scmFacade.findHead(theJob);
        if (source.isEmpty() || head.isEmpty()) {
            return null;
        }
        Optional<SCMRevision> revision = scmFacade.findRevision(source.get(), head.get());
        return revision.flatMap(scmFacade::findHash).orElse(null);
    }
}
