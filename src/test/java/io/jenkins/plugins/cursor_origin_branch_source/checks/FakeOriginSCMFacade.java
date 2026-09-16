package io.jenkins.plugins.cursor_origin_branch_source.checks;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.cursor_origin_branch_source.OriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.Optional;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;

/**
 * A stand-in for the static Jenkins lookups the checks code needs, so that tests can decide what is
 * resolvable without a running Jenkins.
 *
 * <p>{@code findHash} is deliberately not overridden: mapping a revision to the SHA a check is
 * reported against is the behaviour under test, not a lookup to be faked.
 */
class FakeOriginSCMFacade extends OriginSCMFacade {

    @CheckForNull
    private OriginSCMSource source;

    @CheckForNull
    private SCMRevision revision;

    @CheckForNull
    private OriginAppCredentials credentials;

    FakeOriginSCMFacade withSource(@CheckForNull OriginSCMSource source) {
        this.source = source;
        return this;
    }

    FakeOriginSCMFacade withRevision(@CheckForNull SCMRevision revision) {
        this.revision = revision;
        return this;
    }

    FakeOriginSCMFacade withCredentials(@CheckForNull OriginAppCredentials credentials) {
        this.credentials = credentials;
        return this;
    }

    @Override
    Optional<OriginSCMSource> findOriginSCMSource(@NonNull Job<?, ?> job) {
        return Optional.ofNullable(source);
    }

    @Override
    Optional<SCMRevision> findRevision(@NonNull SCMSource source, @NonNull Run<?, ?> run) {
        return Optional.ofNullable(revision);
    }

    /** Empty for a blank id, as the real lookup is, so the tests exercise that branch honestly. */
    @Override
    Optional<OriginAppCredentials> findCredentials(@NonNull Job<?, ?> job, @CheckForNull String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(credentials);
    }
}
