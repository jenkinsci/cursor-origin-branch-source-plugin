package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsSnapshotTaker;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.PathRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.remoting.Channel;
import hudson.util.Secret;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiClient;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.OriginServiceCreateInstallationAccessTokenRequest;
import io.jsonwebtoken.Jwts;
import java.io.Serial;
import java.io.Serializable;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.security.SlaveToMasterCallable;
import jenkins.util.JenkinsJVM;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.SCMVar;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class OriginAppCredentials extends BaseStandardCredentials implements StandardUsernamePasswordCredentials {

    private static final Logger LOGGER = Logger.getLogger(OriginAppCredentials.class.getName());

    /** Overridable in tests to point at a mock server. */
    static String API_BASE_URI = "https://api.cursor.com";

    @Serial
    private static final long serialVersionUID = 1L;

    private final String appId;
    private final String installationId;
    private final Secret privateKey;
    private boolean unrestricted;

    private record Repo(String repoOwner, String repository) implements Serializable {}

    @CheckForNull
    private Repo repo;

    @DataBoundConstructor
    public OriginAppCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String appId,
            String installationId,
            Secret privateKey) {
        super(scope, id, description);
        this.appId = appId;
        this.installationId = installationId;
        this.privateKey = privateKey;
    }

    public String getAppId() {
        return appId;
    }

    public String getInstallationId() {
        return installationId;
    }

    public Secret getPrivateKey() {
        return privateKey;
    }

    @DataBoundSetter
    public void setUnrestricted(boolean unrestricted) {
        this.unrestricted = unrestricted;
    }

    public boolean isUnrestricted() {
        return unrestricted;
    }

    @NonNull
    @Override
    public String getUsername() {
        return "x-access-token";
    }

    @NonNull
    @Override
    public Secret getPassword() {
        checkRestriction();
        return Secret.fromString(mintToken());
    }

    private void checkRestriction() throws SecurityException {
        if (!unrestricted && repo == null) {
            throw new SecurityException("Cannot use restricted credentials " + CredentialsNameProvider.name(this)
                    + " without known repository");
        }
    }

    @Override
    public OriginAppCredentials forContext(Run<?, ?> build, List<DomainRequirement> domainRequirements) {
        if (unrestricted) {
            return this;
        }
        for (var contextualizer : ExtensionList.lookup(Contextualizer.class)) {
            var r = contextualizer.repoOf(build, domainRequirements);
            if (r != null) {
                LOGGER.fine(() -> "found " + r + " in " + build);
                var clone = new OriginAppCredentials(
                        getScope(), getId(), getDescription(), appId, installationId, privateKey);
                clone.repo = r;
                return clone;
            }
        }
        LOGGER.fine(() -> "found nothing for " + build);
        return this;
    }

    public interface Contextualizer extends ExtensionPoint {
        @CheckForNull
        Repo repoOf(Run<?, ?> build, List<DomainRequirement> domainRequirements);
    }

    /**
     * @see GitSCM#lookupScanCredentials
     * @see URIRequirementBuilder
     */
    @Extension(ordinal = 200)
    public static final class GitSCMContextualizer implements Contextualizer {
        @Override
        public Repo repoOf(Run<?, ?> build, List<DomainRequirement> domainRequirements) {
            for (var dr : domainRequirements) {
                if (dr instanceof PathRequirement pr) {
                    var path = pr.getPath();
                    LOGGER.fine(() -> "inspecting " + path);
                    var matcher = Pattern.compile("/([^/]+)/([^/]+?)(?:[.]git)?").matcher(path);
                    if (matcher.matches()) {
                        // TODO this should also verify SchemeRequirement + HostnameRequirement/HostnamePortRequirement
                        return new Repo(matcher.group(1), matcher.group(2));
                    }
                }
            }
            return null;
        }
    }

    /** @see SCMVar */
    @OptionalExtension(requirePlugins = "workflow-multibranch", ordinal = 100)
    public static final class SCMVarContextualizer implements Contextualizer {
        @Override
        public Repo repoOf(Run<?, ?> build, List<DomainRequirement> domainRequirements) {
            var job = build.getParent();
            var property = job.getProperty(BranchJobProperty.class);
            if (property != null) {
                var branch = property.getBranch();
                if (job.getParent() instanceof WorkflowMultiBranchProject workflowMultiBranchProject
                        && workflowMultiBranchProject.getSCMSource(branch.getSourceId())
                                instanceof OriginSCMSource src) {
                    return new Repo(src.getRepoOwner(), src.getRepository());
                }
            } else if (job instanceof WorkflowJob workflowJob
                    && workflowJob.getDefinition() instanceof CpsScmFlowDefinition cpsScmFlowDefinition
                    && cpsScmFlowDefinition.getScm() instanceof GitSCM scm) {
                var urls = scm.getUserRemoteConfigs().stream()
                        .map(UserRemoteConfig::getUrl)
                        .toList();
                LOGGER.fine(() -> "inspecting " + urls);
                if (urls.size() == 1) {
                    var matcher = Pattern.compile(
                                    "\\Q" + OriginSCMSource.GIT_BASE_URL + "\\E/([^/]+)/([^/]+?)(?:[.]git)?")
                            .matcher(urls.get(0));
                    if (matcher.matches()) {
                        return new Repo(matcher.group(1), matcher.group(2));
                    }
                }
            }
            return null;
        }
    }

    /** Mints a fresh installation access token by exchanging a JWT on the controller. */
    String mintToken() {
        // TODO: introduce token caching (see GitHubAppCredentials) if needed
        JenkinsJVM.checkJenkinsJVM();
        return doMintToken(appId, installationId, privateKey.getPlainText(), null, "controller");
    }

    static OriginServiceApi apiWithToken(String bearerToken) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(API_BASE_URI);
        client.setRequestInterceptor(req -> req.header("Authorization", "Bearer " + bearerToken));
        return new OriginServiceApi(client);
    }

    static String doMintToken(
            String appId,
            String installationId,
            String plainPrivateKey,
            @CheckForNull Repo repo,
            String callerContext) {
        try {
            PrivateKey key = parseEd25519Key(plainPrivateKey);
            Instant now = Instant.now();
            String jwt = Jwts.builder()
                    .header()
                    .add("kid", appId)
                    .add("typ", "JWT")
                    .and()
                    .issuer(appId)
                    .claim("aud", "origin-apps")
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(300)))
                    .signWith(key, Jwts.SIG.EdDSA)
                    .compact();
            var req = new OriginServiceCreateInstallationAccessTokenRequest();
            if (repo != null) {
                var id = apiWithToken(doMintToken(appId, installationId, plainPrivateKey, null, callerContext))
                        .originServiceGetRepo(repo.repoOwner, repo.repository)
                        .getId();
                LOGGER.fine(() -> "looked up " + id + " for " + repo);
                req.setRepositoryIds(List.of(id));
                req.setScopes(List.of("repository:contents:read"));
            }
            LOGGER.fine(() -> "Minting installation access token for app=" + appId
                    + " installation=" + installationId
                    + " caller=" + callerContext
                    + " scopes=" + req.getScopes()
                    + " repos=" + req.getRepositoryIds());
            var token = apiWithToken(jwt).originServiceCreateInstallationAccessToken(installationId, req);
            LOGGER.fine(() -> "Minted installation access token for app=" + appId
                    + " installation=" + installationId
                    + " caller=" + callerContext
                    + " expiresAt=" + token.getExpiresAt());
            return token.getToken();
        } catch (ApiException e) {
            throw new RuntimeException("Failed to mint Cursor Origin installation token", e);
        }
    }

    static PrivateKey parseEd25519Key(String pem) {
        try {
            byte[] der = Base64.getDecoder()
                    .decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ed25519 private key", e);
        }
    }

    private Object writeReplace() {
        if (Channel.current() != null) {
            checkRestriction();
            return new DelegatingOriginAppCredentials(
                    getId(),
                    getDescription(),
                    new EncryptedObject<>(
                            new TokenMintingData(appId, installationId, privateKey.getPlainText(), repo)));
        }
        return this;
    }

    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private record TokenMintingData(String appId, String installationId, String privateKey, Repo repo)
            implements Serializable {}

    private record DelegatingOriginAppCredentials(
            String id, String description, EncryptedObject<TokenMintingData> trustedData)
            implements StandardUsernamePasswordCredentials, Serializable {

        @NonNull
        @Override
        public String getId() {
            return id;
        }

        @NonNull
        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public CredentialsScope getScope() {
            return null;
        }

        @Override
        public CredentialsDescriptor getDescriptor() {
            throw new IllegalStateException("not available on agent");
        }

        @NonNull
        @Override
        public String getUsername() {
            return "x-access-token";
        }

        @NonNull
        @Override
        public Secret getPassword() {
            Channel ch = Channel.current();
            if (ch == null) {
                throw new IllegalStateException("DelegatingOriginAppCredentials used on controller");
            }
            try {
                return Secret.fromString(ch.call(new MintToken(trustedData)));
            } catch (Exception e) {
                throw new RuntimeException("Failed to mint token on controller", e);
            }
        }
    }

    private static final class MintToken extends SlaveToMasterCallable<String, Exception> {

        @Serial
        private static final long serialVersionUID = 1L;

        private final EncryptedObject<TokenMintingData> trustedData;

        MintToken(EncryptedObject<TokenMintingData> trustedData) {
            this.trustedData = trustedData;
        }

        @Override
        public String call() throws Exception {
            return doMintToken(
                    trustedData.o().appId(),
                    trustedData.o().installationId(),
                    trustedData.o().privateKey(),
                    trustedData.o().repo,
                    "agent");
        }
    }

    /**
     * Prevents {@code UsernamePasswordCredentialsSnapshotTaker} from freezing the ephemeral
     * installation token into a static credential, which would break subsequent token refreshes.
     */
    @Extension
    public static class OriginAppCredentialsSnapshotTaker extends CredentialsSnapshotTaker<OriginAppCredentials> {
        @Override
        public Class<OriginAppCredentials> type() {
            return OriginAppCredentials.class;
        }

        @Override
        public OriginAppCredentials snapshot(OriginAppCredentials credentials) {
            return credentials;
        }
    }

    @Extension
    public static final class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        @Override
        public String getDisplayName() {
            return "Cursor Origin App";
        }

        @Override
        public String getDescription() {
            return "Allow authentication to Cursor Origin repositories as an app.";
        }

        // could override getIconClassName but Ionicons will not have the Cursor icon

        // TODO: add doTestConnection; also form validation on syntax on all three fields
    }
}
