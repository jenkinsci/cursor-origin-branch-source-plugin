package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal in-memory simulation of the Cursor Origin REST API for use in tests.
 *
 * <p>The server handles real JWT-based authentication: the test registers the app's Ed25519 public
 * key, the server verifies incoming app-JWTs on the token-exchange endpoint, and issues signed
 * {@code oit_eyJ…} access tokens that are re-verified on every subsequent request.
 *
 * <p>All other state (repos, branches, PRs, file trees) lives in memory and is populated via the
 * {@code add*()} builder methods before the test runs.
 */
class MockOriginServer implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(MockOriginServer.class.getName());

    private static final Pattern REPO_PATH = Pattern.compile("^/v1/origin/repos/([^/]+)/([^/]+)(/.*)?$");
    private static final Pattern TOKEN_PATH = Pattern.compile("^/v1/origin/app/installations/([^/]+)/access_tokens$");

    // ── in-memory data model ────────────────────────────────────────────────

    record MockPR(int number, String headBranch, String headSha, String baseBranch, String baseSha, String title) {}

    static class MockBranch {
        final String name;
        final String sha;
        /** path → UTF-8 content; looked up by SHA or name when serving contents requests */
        final Map<String, String> files = new HashMap<>();

        private final MockRepo repo;

        MockBranch(MockRepo repo, String name, String sha) {
            this.repo = repo;
            this.name = name;
            this.sha = sha;
        }

        MockBranch file(String path) {
            files.put(path, "");
            return this;
        }

        MockBranch file(String path, String content) {
            files.put(path, content);
            return this;
        }

        MockBranch branch(String branchName, String branchSha) {
            return repo.branch(branchName, branchSha);
        }

        MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha) {
            return pr(number, headBranch, headSha, baseBranch, baseSha, "PR #" + number);
        }

        MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha, String title) {
            repo.pullRequests.add(new MockPR(number, headBranch, headSha, baseBranch, baseSha, title));
            return repo;
        }
    }

    static class MockRepo {
        final String owner;
        final String name;
        final String id;
        final String defaultBranch;
        final List<MockBranch> branches = new ArrayList<>();
        final List<MockPR> pullRequests = new ArrayList<>();

        MockRepo(String owner, String name, String defaultBranch) {
            this.owner = owner;
            this.name = name;
            this.id = "repo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            this.defaultBranch = defaultBranch;
        }

        MockBranch branch(String branchName, String sha) {
            MockBranch b = new MockBranch(this, branchName, sha);
            branches.add(b);
            return b;
        }

        MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha) {
            return pr(number, headBranch, headSha, baseBranch, baseSha, "PR #" + number);
        }

        MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha, String title) {
            pullRequests.add(new MockPR(number, headBranch, headSha, baseBranch, baseSha, title));
            return this;
        }
    }

    // ── server state ────────────────────────────────────────────────────────

    /** owner → repoName → repo */
    private final Map<String, Map<String, MockRepo>> repos = new HashMap<>();

    /** appId → public key used to verify incoming app-JWTs */
    private final Map<String, PublicKey> appPublicKeys = new ConcurrentHashMap<>();
    /** appId → the scopes this app is approved for at installation time */
    private final Map<String, List<String>> appDefaultScopes = new ConcurrentHashMap<>();
    /** installationId → repo IDs this installation can access; absent means unrestricted */
    private final Map<String, List<String>> installationAccessibleRepoIds = new ConcurrentHashMap<>();

    /** key pair used to sign / verify access tokens */
    private final KeyPair serverKeyPair;

    private final JsonFactory jsonFactory = new JsonFactory();
    private HttpServer server;
    private String baseUrl;

    MockOriginServer() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            serverKeyPair = gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    KeyPair serverKeyPair() {
        return serverKeyPair;
    }

    // ── builder API ─────────────────────────────────────────────────────────

    /**
     * Register an app's public key and the scopes it is approved for.
     *
     * <p>When a token is requested without explicit scopes, all approved scopes are granted; when
     * scopes are requested, only the intersection with approved scopes is granted (unsatisfiable
     * scope requests are silently dropped). Similarly for {@code repositoryIds} when
     * {@link #registerInstallation} has been called.
     */
    MockOriginServer registerApp(String appId, PublicKey publicKey, List<String> approvedScopes) {
        appPublicKeys.put(appId, publicKey);
        appDefaultScopes.put(appId, List.copyOf(approvedScopes));
        return this;
    }

    /**
     * Restrict an installation to a specific set of repo IDs. If not called, the installation
     * can access all repos. When a token is requested with repo IDs outside this set, only the
     * intersection is granted.
     */
    MockOriginServer registerInstallation(String installationId, List<String> accessibleRepoIds) {
        installationAccessibleRepoIds.put(installationId, List.copyOf(accessibleRepoIds));
        return this;
    }

    /** Add a mock repo. Use the returned {@link MockRepo} to populate branches, PRs, files. */
    MockRepo addRepo(@NonNull String owner, @NonNull String name, @NonNull String defaultBranch) {
        MockRepo repo = new MockRepo(owner, name, defaultBranch);
        repos.computeIfAbsent(owner, k -> new HashMap<>()).put(name, repo);
        return repo;
    }

    /** Replace an existing repo with a new one (for simulating mid-test state changes). */
    MockRepo replaceRepo(@NonNull String owner, @NonNull String name, @NonNull String defaultBranch) {
        MockRepo repo = new MockRepo(owner, name, defaultBranch);
        repos.computeIfAbsent(owner, k -> new HashMap<>()).put(name, repo);
        return repo;
    }

    /** Remove a repo (simulating deletion). */
    void removeRepo(@NonNull String owner, @NonNull String name) {
        Map<String, MockRepo> ownerRepos = repos.get(owner);
        if (ownerRepos != null) {
            ownerRepos.remove(name);
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    String baseUrl() {
        return baseUrl;
    }

    String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var filter = new LogAndDispatchFilter();
        server.createContext("/v1/origin/app/installations/", he -> {})
                .getFilters()
                .add(filter);
        server.createContext("/v1/origin/installation/repos", he -> {})
                .getFilters()
                .add(filter);
        server.createContext("/v1/origin/repos/", he -> {}).getFilters().add(filter);
        server.createContext("/v1/origin/keys", he -> {}).getFilters().add(filter);
        server.start();
        InetSocketAddress addr = server.getAddress();
        baseUrl = "http://" + addr.getHostString() + ":" + addr.getPort();
        LOGGER.info("MockOriginServer started at " + baseUrl);
        return baseUrl;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── request dispatch ────────────────────────────────────────────────────

    private void dispatch(HttpExchange he) throws IOException {
        String method = he.getRequestMethod();
        String path = he.getRequestURI().getPath();

        Matcher tokenMatcher = TOKEN_PATH.matcher(path);
        if ("POST".equals(method) && tokenMatcher.matches()) {
            handleTokenExchange(he, tokenMatcher.group(1));
            return;
        }

        if ("/v1/origin/keys".equals(path) && "GET".equals(method)) {
            handleGetJwks(he);
            return;
        }

        if ("/v1/origin/installation/repos".equals(path) && "GET".equals(method)) {
            requireAccessToken(he);
            handleListInstallationRepos(he);
            return;
        }

        Matcher repoMatcher = REPO_PATH.matcher(path);
        if (repoMatcher.matches()) {
            requireAccessToken(he);
            String owner = repoMatcher.group(1);
            String repoName = repoMatcher.group(2);
            String rest = repoMatcher.group(3); // e.g. "/branches", "/pulls", "/contents", null
            MockRepo repo = findRepo(owner, repoName);
            if (repo == null) {
                sendError(he, 404, "repo not found: " + owner + "/" + repoName);
                return;
            }
            if (rest == null || rest.equals("/")) {
                handleGetRepo(he, repo);
            } else if (rest.equals("/branches")) {
                handleListBranches(he, repo);
            } else if (rest.equals("/pulls")) {
                handleListPulls(he, repo);
            } else if (rest.startsWith("/pulls/")) {
                String numberStr = rest.substring("/pulls/".length());
                try {
                    int number = Integer.parseInt(numberStr);
                    handleGetPullRequest(he, repo, number);
                } catch (NumberFormatException e) {
                    sendError(he, 404, "invalid PR number: " + numberStr);
                }
            } else if (rest.equals("/contents")) {
                handleGetContents(he, repo);
            } else if (rest.startsWith("/git/ref/")) {
                handleGetGitRef(he, repo, rest.substring("/git/ref/".length()));
            } else {
                sendError(he, 404, "unknown path: " + path);
            }
            return;
        }

        sendError(he, 404, "unknown path: " + path);
    }

    // ── auth ────────────────────────────────────────────────────────────────

    /**
     * Verifies the incoming app-signed JWT, then issues an {@code oit_} access token signed by
     * the server's own key. The POST body may contain {@code scopes} and {@code repositoryIds}
     * arrays, which are embedded as claims in the issued token for later verification.
     */
    private void handleTokenExchange(HttpExchange he, String installationId) throws IOException {
        String auth = he.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            sendError(he, 401, "missing Bearer token");
            return;
        }
        String jwt = auth.substring("Bearer ".length());
        String appId;
        try {
            var jws = Jwts.parser()
                    .keyLocator(new LocatorAdapter<>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            return appPublicKeys.get(header.getKeyId());
                        }
                    })
                    .build()
                    .parseSignedClaims(jwt);
            appId = jws.getHeader().getKeyId();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "JWT verification failed", e);
            sendError(he, 403, "invalid JWT: " + e.getMessage());
            return;
        }

        // Parse requested scopes/repositoryIds from the request body
        List<String> requestedScopes = new ArrayList<>();
        List<String> requestedRepoIds = new ArrayList<>();
        byte[] bodyBytes = he.getRequestBody().readAllBytes();
        if (bodyBytes.length > 0) {
            try (var parser = jsonFactory.createParser(bodyBytes)) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.FIELD_NAME) {
                        String field = parser.currentName();
                        parser.nextToken();
                        if ("scopes".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                if (parser.currentToken() == JsonToken.VALUE_STRING) {
                                    requestedScopes.add(parser.getText());
                                }
                            }
                        } else if ("repositoryIds".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                if (parser.currentToken() == JsonToken.VALUE_STRING) {
                                    requestedRepoIds.add(parser.getText());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to parse token exchange body", e);
                sendError(he, 400, "invalid request body: " + e.getMessage());
                return;
            }
        }

        // Effective scopes = requested ∩ approved; if no scopes requested, use all approved
        List<String> approvedScopes = appDefaultScopes.getOrDefault(appId, List.of());
        List<String> effectiveScopes;
        if (requestedScopes.isEmpty()) {
            effectiveScopes = new ArrayList<>(approvedScopes);
        } else {
            effectiveScopes = new ArrayList<>(requestedScopes);
            effectiveScopes.retainAll(approvedScopes);
        }
        // repository:metadata:read is always present in any non-empty scoped token
        if (!effectiveScopes.isEmpty() && !effectiveScopes.contains("repository:metadata:read")) {
            effectiveScopes.add("repository:metadata:read");
        }

        // Effective repoIds = requested ∩ installation's accessible repos; absent = unrestricted
        List<String> accessibleRepos = installationAccessibleRepoIds.get(installationId);
        List<String> effectiveRepoIds;
        if (accessibleRepos == null) {
            effectiveRepoIds = new ArrayList<>(requestedRepoIds);
        } else if (requestedRepoIds.isEmpty()) {
            effectiveRepoIds = new ArrayList<>();
        } else {
            effectiveRepoIds = new ArrayList<>(requestedRepoIds);
            effectiveRepoIds.retainAll(accessibleRepos);
        }

        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofHours(1));
        var builder = Jwts.builder()
                .subject(installationId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString());
        if (!effectiveScopes.isEmpty()) {
            builder.claim("scopes", effectiveScopes);
        }
        if (!effectiveRepoIds.isEmpty()) {
            builder.claim("repositoryIds", effectiveRepoIds);
        }
        String payload = builder.signWith(serverKeyPair.getPrivate()).compact();
        String accessToken = "oit_" + payload;

        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeStringField("token", accessToken);
            gen.writeStringField("expiresAt", exp.toString());
            gen.writeEndObject();
        });
    }

    private void requireAccessToken(HttpExchange he) {
        String auth = he.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new HaltException(401, "missing Bearer token");
        }
        String token = auth.substring("Bearer ".length());
        try {
            String jwtPart = token.startsWith("oit_") ? token.substring(4) : token;
            Jwts.parser().verifyWith(serverKeyPair.getPublic()).build().parseSignedClaims(jwtPart);
        } catch (Exception e) {
            throw new HaltException(401, "invalid access token");
        }
    }

    // ── endpoint handlers ───────────────────────────────────────────────────

    private void handleGetJwks(HttpExchange he) throws IOException {
        byte[] encoded = serverKeyPair.getPublic().getEncoded();
        byte[] rawKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey);
        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("keys");
            gen.writeStartObject();
            gen.writeStringField("kty", "OKP");
            gen.writeStringField("crv", "Ed25519");
            gen.writeStringField("x", x);
            gen.writeEndObject();
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    /**
     * Signs and delivers a webhook event to the given URL, emulating the Cursor Origin server.
     *
     * @param hookUrl destination URL (e.g. Jenkins root + "cursor-origin-webhook/")
     * @param appId app ID for the envelope
     * @param installationId installation ID for the envelope
     * @param eventType event type slug (e.g. "repository.pushed")
     * @param payloadWriter writes the event payload JSON object
     */
    void deliverWebhook(String hookUrl, String appId, String installationId, String eventType, JsonWriter payloadWriter)
            throws Exception {
        String deliveryId = "whd_" + UUID.randomUUID().toString().replace("-", "");
        long ts = Instant.now().getEpochSecond();

        ByteArrayOutputStream bodyOs = new ByteArrayOutputStream();
        try (JsonGenerator gen = jsonFactory.createGenerator(bodyOs)) {
            gen.writeStartObject();
            gen.writeStringField("deliveryId", deliveryId);
            gen.writeStringField("appId", appId);
            gen.writeStringField("installationId", installationId);
            gen.writeObjectFieldStart("event");
            gen.writeStringField("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
            gen.writeStringField("type", eventType);
            gen.writeStringField("eventTime", Instant.now().toString());
            gen.writeFieldName("payload");
            payloadWriter.write(gen);
            gen.writeEndObject();
            gen.writeEndObject();
        }
        byte[] body = bodyOs.toByteArray();

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((deliveryId + "." + ts + ".").getBytes(StandardCharsets.UTF_8));
        md.update(body);
        String digestHex = HexFormat.of().formatHex(md.digest());

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(serverKeyPair.getPrivate());
        signer.update(digestHex.getBytes(StandardCharsets.UTF_8));
        String sig64 = Base64.getEncoder().encodeToString(signer.sign());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(hookUrl))
                .header("Content-Type", "application/json")
                .header("user-agent", "Cursor-Origin-Webhook/1.0")
                .header("webhook-id", deliveryId)
                .header("webhook-timestamp", String.valueOf(ts))
                .header("webhook-signature", "v1ed," + sig64)
                .header("webhook-event-type", eventType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Webhook delivery failed: HTTP " + resp.statusCode() + " - " + resp.body());
        }
    }

    private void handleListInstallationRepos(HttpExchange he) throws IOException {
        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("repositories");
            for (Map<String, MockRepo> ownerRepos : repos.values()) {
                for (MockRepo repo : ownerRepos.values()) {
                    writeRepoObject(gen, repo);
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    private void handleGetRepo(HttpExchange he, MockRepo repo) throws IOException {
        sendJson(he, 200, gen -> writeRepoObject(gen, repo));
    }

    private void handleListBranches(HttpExchange he, MockRepo repo) throws IOException {
        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("branches");
            for (MockBranch b : repo.branches) {
                gen.writeStartObject();
                gen.writeStringField("name", b.name);
                gen.writeObjectFieldStart("commit");
                gen.writeStringField("sha", b.sha);
                gen.writeEndObject();
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    private void handleListPulls(HttpExchange he, MockRepo repo) throws IOException {
        String stateFilter = queryParam(he, "state");
        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("pullRequests");
            for (MockPR pr : repo.pullRequests) {
                if (stateFilter != null && !stateFilter.equals("open")) {
                    continue; // mock only has open PRs
                }
                gen.writeStartObject();
                gen.writeStringField("number", String.valueOf(pr.number()));
                gen.writeStringField("state", "open");
                gen.writeStringField("title", pr.title());
                gen.writeObjectFieldStart("head");
                gen.writeStringField("ref", pr.headBranch());
                gen.writeStringField("sha", pr.headSha());
                gen.writeEndObject();
                gen.writeObjectFieldStart("base");
                gen.writeStringField("ref", pr.baseBranch());
                gen.writeStringField("sha", pr.baseSha());
                gen.writeEndObject();
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }

    private void handleGetPullRequest(HttpExchange he, MockRepo repo, int number) throws IOException {
        for (MockPR pr : repo.pullRequests) {
            if (pr.number() == number) {
                sendJson(he, 200, gen -> {
                    gen.writeStartObject();
                    gen.writeStringField("number", String.valueOf(pr.number()));
                    gen.writeStringField("state", "open");
                    gen.writeStringField("title", pr.title());
                    gen.writeObjectFieldStart("head");
                    gen.writeStringField("ref", pr.headBranch());
                    gen.writeStringField("sha", pr.headSha());
                    gen.writeEndObject();
                    gen.writeObjectFieldStart("base");
                    gen.writeStringField("ref", pr.baseBranch());
                    gen.writeStringField("sha", pr.baseSha());
                    gen.writeEndObject();
                    gen.writeEndObject();
                });
                return;
            }
        }
        sendError(he, 404, "PR not found: " + number);
    }

    private void handleGetContents(HttpExchange he, MockRepo repo) throws IOException {
        String path = queryParam(he, "path");
        String ref = queryParam(he, "ref");
        Map<String, String> files = findFilesForRef(repo, ref);
        if (path == null || path.isBlank()) {
            sendJson(he, 200, gen -> {
                gen.writeStartObject();
                gen.writeStringField("type", "dir");
                gen.writeStringField("name", "");
                gen.writeStringField("path", "");
                gen.writeArrayFieldStart("entries");
                for (String f : files.keySet()) {
                    gen.writeStartObject();
                    gen.writeStringField("type", "file");
                    gen.writeStringField("name", f);
                    gen.writeStringField("path", f);
                    gen.writeEndObject();
                }
                gen.writeEndArray();
                gen.writeEndObject();
            });
            return;
        }
        String content = files.get(path);
        if (content != null) {
            String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'})
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
            sendJson(he, 200, gen -> {
                gen.writeStartObject();
                gen.writeStringField("type", "file");
                gen.writeStringField("name", path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
                gen.writeStringField("path", path);
                gen.writeStringField("encoding", "base64");
                gen.writeStringField("content", b64);
                gen.writeEndObject();
            });
        } else {
            sendError(he, 404, "path not found: " + path);
        }
    }

    private void handleGetGitRef(HttpExchange he, MockRepo repo, String ref) throws IOException {
        if (ref.startsWith("heads/")) {
            String branchName = ref.substring("heads/".length());
            for (MockBranch b : repo.branches) {
                if (b.name.equals(branchName)) {
                    final String sha = b.sha;
                    sendJson(he, 200, gen -> {
                        gen.writeStartObject();
                        gen.writeStringField("ref", "refs/" + ref);
                        gen.writeObjectFieldStart("object");
                        gen.writeStringField("sha", sha);
                        gen.writeStringField("type", "commit");
                        gen.writeEndObject();
                        gen.writeEndObject();
                    });
                    return;
                }
            }
        }
        sendError(he, 404, "ref not found: " + ref);
    }

    private static Map<String, String> findFilesForRef(MockRepo repo, String ref) {
        if (ref != null) {
            for (MockBranch b : repo.branches) {
                if (ref.equals(b.sha)) {
                    return b.files;
                }
            }
            for (MockBranch b : repo.branches) {
                if (ref.equals(b.name)) {
                    return b.files;
                }
            }
        }
        return repo.branches.isEmpty() ? Map.of() : repo.branches.get(0).files;
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    @FunctionalInterface
    interface JsonWriter {
        void write(JsonGenerator gen) throws IOException;
    }

    private void writeRepoObject(JsonGenerator gen, MockRepo repo) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", repo.id);
        gen.writeStringField("name", repo.name);
        gen.writeStringField("fullName", repo.owner + "/" + repo.name);
        gen.writeStringField("defaultBranch", repo.defaultBranch);
        gen.writeStringField("cloneUrl", OriginSCMSource.GIT_BASE_URL + "/" + repo.owner + "/" + repo.name + ".git");
        gen.writeObjectFieldStart("owner");
        gen.writeStringField("slug", repo.owner);
        gen.writeEndObject();
        gen.writeEndObject();
    }

    private void sendJson(HttpExchange he, int status, JsonWriter writer) throws IOException {
        he.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        he.sendResponseHeaders(status, 0);
        try (JsonGenerator gen = jsonFactory.createGenerator(he.getResponseBody())) {
            writer.write(gen);
        }
    }

    private static void sendError(HttpExchange he, int status, String message) throws IOException {
        byte[] body = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        he.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        he.sendResponseHeaders(status, body.length);
        try (var os = he.getResponseBody()) {
            os.write(body);
        }
    }

    // ── utilities ────────────────────────────────────────────────────────────

    private MockRepo findRepo(String owner, String repoName) {
        Map<String, MockRepo> ownerRepos = repos.get(owner);
        return ownerRepos != null ? ownerRepos.get(repoName) : null;
    }

    private static String queryParam(HttpExchange he, String name) {
        String query = he.getRequestURI().getQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }

    static final class HaltException extends RuntimeException {
        final int code;
        final String message;

        HaltException(int code, String message) {
            super(message);
            this.code = code;
            this.message = message;
        }
    }

    /** Logs every request, dispatches to {@link #dispatch}, and catches {@link HaltException}. */
    private class LogAndDispatchFilter extends Filter {
        @Override
        public void doFilter(HttpExchange he, Chain chain) throws IOException {
            LOGGER.fine(() -> he.getRequestMethod() + " " + he.getRequestURI());
            try {
                dispatch(he);
            } catch (HaltException x) {
                sendError(he, x.code, x.message);
            } finally {
                he.close();
            }
        }

        @Override
        public String description() {
            return "dispatch";
        }
    }
}
