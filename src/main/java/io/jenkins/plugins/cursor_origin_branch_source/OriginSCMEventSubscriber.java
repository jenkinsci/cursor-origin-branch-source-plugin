package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.scm.SCM;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceEvent;

/**
 * Translates verified Cursor Origin webhook events into SCM API head/source events that drive
 * multibranch project indexing and build triggering.
 */
@Extension
public class OriginSCMEventSubscriber implements OriginEventSubscriber {

    private static final Logger LOGGER = Logger.getLogger(OriginSCMEventSubscriber.class.getName());

    @Override
    public void onEvent(OriginWebhookEvent event) {
        String eventType = event.eventType();
        JsonNode payload = event.payload();
        long ts = event.timestamp().toEpochMilli();
        String origin = event.origin();

        switch (eventType) {
            case "repository.pushed" -> handlePush(payload, ts, origin);
            case "pull_request.created", "pull_request.reopened", "pull_request.published" ->
                handlePullRequest(payload, SCMEvent.Type.CREATED, ts, origin);
            case "pull_request.closed", "pull_request.merged" ->
                handlePullRequest(payload, SCMEvent.Type.REMOVED, ts, origin);
            case "pull_request.head_ref.pushed" -> handlePullRequest(payload, SCMEvent.Type.UPDATED, ts, origin);
            case "repository.created" -> handleRepositoryEvent(payload, SCMEvent.Type.CREATED, ts, origin);
            case "repository.deleted" -> handleRepositoryEvent(payload, SCMEvent.Type.REMOVED, ts, origin);
            default -> LOGGER.fine(() -> "No SCM mapping for webhook event type: " + eventType);
        }
    }

    private void handlePush(JsonNode payload, long timestamp, String origin) {
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("slug").asText("");
        String repoName = repo.path("name").asText("");
        if (owner.isEmpty() || repoName.isEmpty()) return;

        for (JsonNode update : payload.path("refUpdates")) {
            String ref = update.path("ref").asText("");
            if (!ref.startsWith("refs/heads/")) continue;
            String branchName = ref.substring("refs/heads/".length());
            boolean deleted = update.path("afterIsEmpty").asBoolean(false);
            boolean created = update.path("beforeIsEmpty").asBoolean(false);
            String newSha = deleted ? null : update.path("after").asText(null);
            SCMEvent.Type type =
                    deleted ? SCMEvent.Type.REMOVED : created ? SCMEvent.Type.CREATED : SCMEvent.Type.UPDATED;
            LOGGER.fine(() -> "Push " + type + ": " + owner + "/" + repoName + " " + branchName);
            SCMHeadEvent.fireLater(
                    new BranchHeadEvent(type, timestamp, owner, repoName, branchName, newSha, origin),
                    0,
                    TimeUnit.SECONDS);
        }
    }

    private void handlePullRequest(JsonNode payload, SCMEvent.Type type, long timestamp, String origin) {
        JsonNode pr = payload.path("pullRequest");
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("slug").asText("");
        String repoName = repo.path("name").asText("");
        String prNumber = pr.path("number").asText("");
        String headBranch = pr.path("head").path("ref").asText("");
        String headSha = pr.path("head").path("sha").asText("");
        String baseBranch = pr.path("base").path("ref").asText("");
        String baseSha = pr.path("base").path("sha").asText("");
        if (owner.isEmpty() || repoName.isEmpty() || prNumber.isEmpty()) return;

        LOGGER.fine(() -> "PR " + type + ": " + owner + "/" + repoName + " PR-" + prNumber);
        SCMHeadEvent.fireLater(
                new PullRequestHeadEvent(
                        type, timestamp, owner, repoName, prNumber, headBranch, headSha, baseBranch, baseSha, origin),
                0,
                TimeUnit.SECONDS);
    }

    private void handleRepositoryEvent(JsonNode payload, SCMEvent.Type type, long timestamp, String origin) {
        JsonNode repo = payload.path("repository");
        String owner = repo.path("owner").path("slug").asText("");
        String repoName = repo.path("name").asText("");
        if (owner.isEmpty() || repoName.isEmpty()) return;

        LOGGER.fine(() -> "Repository " + type + ": " + owner + "/" + repoName);
        SCMSourceEvent.fireLater(
                new RepositorySourceEvent(type, timestamp, owner, repoName, origin), 0, TimeUnit.SECONDS);
    }

    // ── SCM event implementations ────────────────────────────────────────────

    static final class BranchHeadEvent extends SCMHeadEvent<String> {

        private final String repoOwner;
        private final String repoName;
        private final String branchName;
        private final String sha;

        BranchHeadEvent(
                SCMEvent.Type type,
                long timestamp,
                String repoOwner,
                String repoName,
                String branchName,
                String sha,
                String origin) {
            super(type, timestamp, branchName, origin);
            this.repoOwner = repoOwner;
            this.repoName = repoName;
            this.branchName = branchName;
            this.sha = sha;
        }

        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            return navigator instanceof OriginSCMNavigator nav && repoOwner.equals(nav.getRepoOwner());
        }

        @Override
        public String getSourceName() {
            return repoName;
        }

        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            if (!(source instanceof OriginSCMSource src)
                    || !repoOwner.equals(src.getRepoOwner())
                    || !repoName.equals(src.getRepository())) {
                return Map.of();
            }
            SCMHead head = new SCMHead(branchName);
            if (sha == null || sha.isBlank()) {
                return Collections.singletonMap(head, null);
            }
            return Map.of(head, new AbstractGitSCMSource.SCMRevisionImpl(head, sha));
        }

        @Override
        public boolean isMatch(@NonNull SCM scm) {
            return false;
        }

        @Override
        public String description() {
            return "Push to branch " + branchName + " in " + repoOwner + "/" + repoName;
        }
    }

    static final class PullRequestHeadEvent extends SCMHeadEvent<String> {

        private final String repoOwner;
        private final String repoName;
        private final String prNumber;
        private final String headBranch;
        private final String headSha;
        private final String baseBranch;
        private final String baseSha;

        PullRequestHeadEvent(
                SCMEvent.Type type,
                long timestamp,
                String repoOwner,
                String repoName,
                String prNumber,
                String headBranch,
                String headSha,
                String baseBranch,
                String baseSha,
                String origin) {
            super(type, timestamp, prNumber, origin);
            this.repoOwner = repoOwner;
            this.repoName = repoName;
            this.prNumber = prNumber;
            this.headBranch = headBranch;
            this.headSha = headSha;
            this.baseBranch = baseBranch;
            this.baseSha = baseSha;
        }

        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            return navigator instanceof OriginSCMNavigator nav && repoOwner.equals(nav.getRepoOwner());
        }

        @Override
        public String getSourceName() {
            return repoName;
        }

        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            if (!(source instanceof OriginSCMSource src)
                    || !repoOwner.equals(src.getRepoOwner())
                    || !repoName.equals(src.getRepository())) {
                return Map.of();
            }
            OriginPullRequestSCMHead head = new OriginPullRequestSCMHead(prNumber, headBranch, baseBranch);
            if (getType() == SCMEvent.Type.REMOVED) {
                return Collections.singletonMap(head, null);
            }
            return Map.of(head, new OriginPullRequestSCMRevision(head, headSha, baseSha));
        }

        @Override
        public boolean isMatch(@NonNull SCM scm) {
            return false;
        }

        @Override
        public String description() {
            return "PR-" + prNumber + " " + getType().name().toLowerCase(Locale.ROOT) + " in " + repoOwner + "/"
                    + repoName;
        }
    }

    static final class RepositorySourceEvent extends SCMSourceEvent<String> {

        private final String repoOwner;
        private final String repoName;

        RepositorySourceEvent(SCMEvent.Type type, long timestamp, String repoOwner, String repoName, String origin) {
            super(type, timestamp, repoName, origin);
            this.repoOwner = repoOwner;
            this.repoName = repoName;
        }

        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            return navigator instanceof OriginSCMNavigator nav && repoOwner.equals(nav.getRepoOwner());
        }

        @Override
        public boolean isMatch(@NonNull SCMSource source) {
            return source instanceof OriginSCMSource src
                    && repoOwner.equals(src.getRepoOwner())
                    && repoName.equals(src.getRepository());
        }

        @Override
        public String getSourceName() {
            return repoName;
        }

        @Override
        public String description() {
            return "Repository " + repoOwner + "/" + repoName + " "
                    + getType().name().toLowerCase(Locale.ROOT);
        }
    }
}
