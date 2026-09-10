package io.jenkins.plugins.cursor_origin_branch_source.checks;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import io.jenkins.plugins.cursor_origin_branch_source.OriginAppCredentials;
import io.jenkins.plugins.cursor_origin_branch_source.OriginPullRequestSCMRevision;
import io.jenkins.plugins.cursor_origin_branch_source.OriginSCMSource;
import java.util.Collections;
import java.util.Optional;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;

/**
 * Facade over the static Jenkins SCM and credentials lookups needed to publish checks.
 *
 * <p>All of them are static methods on Jenkins core or plugin classes, which makes the classes that
 * use them hard to unit test. Routing every lookup through this class provides a single seam that
 * tests can replace with a mock.
 */
class OriginSCMFacade {

    /** Finds the {@link OriginSCMSource} backing {@code job}, if the job is Origin backed at all. */
    Optional<OriginSCMSource> findOriginSCMSource(@NonNull Job<?, ?> job) {
        SCMSource source = SCMSource.SourceByItem.findSource(job);
        return source instanceof OriginSCMSource originSource ? Optional.of(originSource) : Optional.empty();
    }

    /**
     * Reads the revision that {@code run} was built from, as recorded on the build itself.
     *
     * <p>This is the only way a revision is resolved, and it performs no remote call. Fetching the
     * current head of a branch instead would be a guess: the head is not settled until the build has
     * checked it out, so a revision resolved any earlier can be one the build never builds.
     */
    Optional<SCMRevision> findRevision(@NonNull SCMSource source, @NonNull Run<?, ?> run) {
        return Optional.ofNullable(SCMRevisionAction.getRevision(source, run));
    }

    /**
     * Extracts the commit SHA that a check run should be reported against.
     *
     * <p>For a change request this is deliberately the head of the pull request branch rather than
     * any merge commit: a check reported against an ephemeral merge commit would not surface on the
     * pull request.
     */
    Optional<String> findHash(@NonNull SCMRevision revision) {
        if (revision instanceof OriginPullRequestSCMRevision prRevision) {
            return Optional.of(prRevision.getHeadHash());
        }
        if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl gitRevision) {
            return Optional.of(gitRevision.getHash());
        }
        return Optional.empty();
    }

    /** Looks up the Cursor Origin app credentials that {@code job}'s source is configured with. */
    Optional<OriginAppCredentials> findCredentials(@NonNull Job<?, ?> job, @CheckForNull String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CredentialsProvider.findCredentialByIdInItem(
                credentialsId, OriginAppCredentials.class, job, ACL.SYSTEM2, Collections.emptyList()));
    }
}
