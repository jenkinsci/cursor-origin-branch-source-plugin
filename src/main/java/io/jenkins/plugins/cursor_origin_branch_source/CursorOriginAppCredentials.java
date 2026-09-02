package io.jenkins.plugins.cursor_origin_branch_source;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsSnapshotTaker;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.remoting.Channel;
import hudson.util.Secret;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiClient;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.ApiException;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.api.OriginServiceApi;
import io.jenkins.plugins.cursor_origin_branch_source.origin_openapi.model.InstallationAccessToken;
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
import jenkins.security.SlaveToMasterCallable;
import jenkins.util.JenkinsJVM;
import org.kohsuke.stapler.DataBoundConstructor;

public class CursorOriginAppCredentials extends BaseStandardCredentials implements StandardUsernamePasswordCredentials {

    static final String API_BASE_URI = "https://api.cursor.com";

    @Serial
    private static final long serialVersionUID = 1L;

    private final String appId;
    private final Secret privateKey;
    private final String installationId;

    @DataBoundConstructor
    public CursorOriginAppCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String appId,
            Secret privateKey,
            String installationId) {
        super(scope, id, description);
        this.appId = appId;
        this.privateKey = privateKey;
        this.installationId = installationId;
    }

    @NonNull
    @Override
    public String getUsername() {
        return "x-access-token";
    }

    @NonNull
    @Override
    public Secret getPassword() {
        return Secret.fromString(mintToken());
    }

    @Override
    public CursorOriginAppCredentials forRun(Run<?, ?> run) {
        // TODO: infer repository context from OriginSCMSource once that class exists
        return this;
    }

    /** Mints a fresh installation access token by exchanging a JWT on the controller. */
    String mintToken() {
        // TODO: introduce token caching (see GitHubAppCredentials) if needed
        JenkinsJVM.checkJenkinsJVM();
        return doMintToken(appId, installationId, privateKey.getPlainText());
    }

    static OriginServiceApi apiWithToken(String bearerToken) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(API_BASE_URI);
        client.setRequestInterceptor(req -> req.header("Authorization", "Bearer " + bearerToken));
        return new OriginServiceApi(client);
    }

    static String doMintToken(String appId, String installationId, String plainPrivateKey) {
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
            // TODO: pass repositoryIds for per-repo scoping
            //   (see OriginServiceCreateInstallationAccessTokenRequest.repositoryIds)
            InstallationAccessToken token = apiWithToken(jwt)
                    .originServiceCreateInstallationAccessToken(
                            installationId, new OriginServiceCreateInstallationAccessTokenRequest());
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
            return new DelegatingCursorOriginAppCredentials(
                    getId(),
                    getDescription(),
                    new EncryptedObject<>(new TokenMintingData(appId, installationId, privateKey.getEncryptedValue())));
        }
        return this;
    }

    /**
     * Prevents {@code UsernamePasswordCredentialsSnapshotTaker} from freezing the ephemeral
     * installation token into a static credential, which would break subsequent token refreshes.
     */
    @Extension
    public static class CursorOriginAppCredentialsSnapshotTaker
            extends CredentialsSnapshotTaker<CursorOriginAppCredentials> {
        @Override
        public Class<CursorOriginAppCredentials> type() {
            return CursorOriginAppCredentials.class;
        }

        @Override
        public CursorOriginAppCredentials snapshot(CursorOriginAppCredentials credentials) {
            return credentials;
        }
    }

    record TokenMintingData(String appId, String installationId, String encryptedPrivateKey) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class DelegatingCursorOriginAppCredentials
            implements StandardUsernamePasswordCredentials, Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String description;
        private final EncryptedObject<TokenMintingData> trustedData;

        DelegatingCursorOriginAppCredentials(
                String id, String description, EncryptedObject<TokenMintingData> trustedData) {
            this.id = id;
            this.description = description;
            this.trustedData = trustedData;
        }

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
        public com.cloudbees.plugins.credentials.CredentialsDescriptor getDescriptor() {
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
                throw new IllegalStateException("DelegatingCursorOriginAppCredentials used on controller");
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
            TokenMintingData data = trustedData.o();
            return doMintToken(
                    data.appId(),
                    data.installationId(),
                    Secret.fromString(data.encryptedPrivateKey()).getPlainText());
        }
    }

    @Extension
    public static final class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Cursor Origin App";
        }
        // TODO: add doTestConnection
    }
}
