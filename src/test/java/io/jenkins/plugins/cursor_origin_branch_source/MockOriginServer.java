package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.InputStream;
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
import java.time.OffsetDateTime;
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
public class MockOriginServer implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(MockOriginServer.class.getName());

    private static final Pattern REPO_PATH = Pattern.compile("^/v1/origin/repos/([^/]+)/([^/]+)(/.*)?$");
    private static final Pattern TOKEN_PATH = Pattern.compile("^/v1/origin/app/installations/([^/]+)/access_tokens$");
    private static final Pattern ANNOTATIONS_PATH = Pattern.compile("^/check-runs/([^/]+)/annotations$");

    private static final String BODY_ATTRIBUTE = "mock-origin-request-body";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cursor Origin accepts 1-25 annotations per request and stores at most 100 per check run. */
    private static final int MIN_ANNOTATION_BATCH = 1;

    private static final int MAX_ANNOTATION_BATCH = 25;
    private static final int MAX_ANNOTATIONS_PER_CHECK_RUN = 100;

    // ── in-memory data model ────────────────────────────────────────────────

    public record MockPR(int number, String headBranch, String headSha, String baseBranch, String baseSha) {}

    public static class MockBranch {
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

        public MockBranch file(String path) {
            files.put(path, "");
            return this;
        }

        public MockBranch file(String path, String content) {
            files.put(path, content);
            return this;
        }

        public MockBranch branch(String branchName, String branchSha) {
            return repo.branch(branchName, branchSha);
        }

        public MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha) {
            repo.pullRequests.add(new MockPR(number, headBranch, headSha, baseBranch, baseSha));
            return repo;
        }
    }

    /** An annotation appended to a {@link MockCheckRun}. */
    public record MockAnnotation(
            String level, String message, String title, String path, Integer startLine, Integer endLine) {}

    /**
     * A check run as reported by the plugin. Cursor Origin upserts on
     * {@code (repository, head SHA, suite key, check key)}, so repeated reports of the same check
     * update the same instance and are recorded in {@link #reportedStates()}.
     */
    public static class MockCheckRun {
        private final String id;
        private final String headSha;
        private final String suiteKey;
        private final String suiteName;
        private final String key;
        private String name;
        private String status;
        private String conclusion;
        private String detailsUrl;
        private String externalId;
        private String startedAt;
        private String completedAt;
        private String outputTitle;
        private String outputSummary;
        private String outputText;
        private OffsetDateTime externalUpdatedAt;
        private final List<MockAnnotation> annotations = new ArrayList<>();
        private final List<String> reportedStates = new ArrayList<>();

        MockCheckRun(String id, String headSha, String suiteKey, String suiteName, String key) {
            this.id = id;
            this.headSha = headSha;
            this.suiteKey = suiteKey;
            this.suiteName = suiteName;
            this.key = key;
        }

        public String getId() {
            return id;
        }

        public String getHeadSha() {
            return headSha;
        }

        public String getSuiteKey() {
            return suiteKey;
        }

        public String getSuiteName() {
            return suiteName;
        }

        public String getKey() {
            return key;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public String getConclusion() {
            return conclusion;
        }

        public String getDetailsUrl() {
            return detailsUrl;
        }

        public String getExternalId() {
            return externalId;
        }

        public String getStartedAt() {
            return startedAt;
        }

        public String getCompletedAt() {
            return completedAt;
        }

        public String getOutputTitle() {
            return outputTitle;
        }

        public String getOutputSummary() {
            return outputSummary;
        }

        public String getOutputText() {
            return outputText;
        }

        public List<MockAnnotation> getAnnotations() {
            return List.copyOf(annotations);
        }

        /**
         * The status of every report of this check run, in order, with the conclusion appended once
         * it completes, e.g. {@code ["queued", "in_progress", "completed/success"]}.
         */
        public List<String> reportedStates() {
            return List.copyOf(reportedStates);
        }
    }

    public static class MockRepo {
        final String owner;
        final String name;
        final String defaultBranch;
        final List<MockBranch> branches = new ArrayList<>();
        final List<MockPR> pullRequests = new ArrayList<>();
        final List<MockCheckRun> checkRuns = new ArrayList<>();

        MockRepo(String owner, String name, String defaultBranch) {
            this.owner = owner;
            this.name = name;
            this.defaultBranch = defaultBranch;
        }

        public MockBranch branch(String branchName, String sha) {
            MockBranch b = new MockBranch(this, branchName, sha);
            branches.add(b);
            return b;
        }

        public MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha) {
            pullRequests.add(new MockPR(number, headBranch, headSha, baseBranch, baseSha));
            return this;
        }
    }

    // ── server state ────────────────────────────────────────────────────────

    /** owner → repoName → repo */
    private final Map<String, Map<String, MockRepo>> repos = new HashMap<>();

    /** appId → public key used to verify incoming app-JWTs */
    private final Map<String, PublicKey> appPublicKeys = new ConcurrentHashMap<>();

    /** when set, annotation requests are rejected with this status instead of being stored */
    private Integer annotationFailureStatus;

    /** key pair used to sign / verify access tokens */
    private final KeyPair serverKeyPair;

    private final JsonFactory jsonFactory = new JsonFactory();
    private HttpServer server;
    private String baseUrl;

    public MockOriginServer() {
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

    /** Register an app's public key so that JWTs it signs will be accepted. */
    public MockOriginServer registerApp(String appId, PublicKey publicKey) {
        appPublicKeys.put(appId, publicKey);
        return this;
    }

    /** Add a mock repo. Use the returned {@link MockRepo} to populate branches, PRs, files. */
    public MockRepo addRepo(@NonNull String owner, @NonNull String name, @NonNull String defaultBranch) {
        MockRepo repo = new MockRepo(owner, name, defaultBranch);
        repos.computeIfAbsent(owner, k -> new HashMap<>()).put(name, repo);
        return repo;
    }

    /** Makes every subsequent annotation request fail, to exercise the publisher's error handling. */
    public MockOriginServer rejectAnnotationsWith(int status) {
        annotationFailureStatus = status;
        return this;
    }

    // ── assertions ──────────────────────────────────────────────────────────

    /** Every check run reported against a repo, in the order it was first reported. */
    public List<MockCheckRun> checkRuns(@NonNull String owner, @NonNull String repoName) {
        MockRepo repo = findRepo(owner, repoName);
        return repo == null ? List.of() : List.copyOf(repo.checkRuns);
    }

    /** The single check run reported under {@code checkKey}, failing if there is not exactly one. */
    public MockCheckRun checkRun(@NonNull String owner, @NonNull String repoName, @NonNull String checkKey) {
        List<MockCheckRun> matching = checkRuns(owner, repoName).stream()
                .filter(run -> run.key.equals(checkKey))
                .toList();
        if (matching.size() != 1) {
            throw new AssertionError(
                    "expected exactly one check run with key '" + checkKey + "' but found " + matching);
        }
        return matching.get(0);
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

    public String start() throws IOException {
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
            } else if (rest.equals("/contents")) {
                handleGetContents(he, repo);
            } else if (rest.equals("/check-runs") && "POST".equals(method)) {
                handlePostCheckRun(he, repo);
            } else if ("POST".equals(method) && ANNOTATIONS_PATH.matcher(rest).matches()) {
                Matcher annotationMatcher = ANNOTATIONS_PATH.matcher(rest);
                annotationMatcher.matches();
                handleCreateCheckRunAnnotations(he, repo, annotationMatcher.group(1));
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
     * the server's own key.
     */
    private void handleTokenExchange(HttpExchange he, String installationId) throws IOException {
        String auth = he.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            sendError(he, 401, "missing Bearer token");
            return;
        }
        String jwt = auth.substring("Bearer ".length());
        try {
            Jwts.parser()
                    .keyLocator(new LocatorAdapter<>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            return appPublicKeys.get(header.getKeyId());
                        }
                    })
                    .build()
                    .parseSignedClaims(jwt);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "JWT verification failed", e);
            sendError(he, 403, "invalid JWT: " + e.getMessage());
            return;
        }

        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofHours(1));
        String payload = Jwts.builder()
                .subject(installationId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(serverKeyPair.getPrivate())
                .compact();
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
                gen.writeStringField("title", "PR #" + pr.number());
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

    /**
     * Upserts a check suite and check run, rejecting requests that break the parts of the Origin
     * contract a publisher has to get right: the required identity fields, and a conclusion exactly
     * when the check run has completed.
     */
    private void handlePostCheckRun(HttpExchange he, MockRepo repo) throws IOException {
        JsonNode body = requestBody(he);
        String headSha = requireText(body, "headSha");

        JsonNode suite = body.path("checkSuite");
        String suiteKey = requireText(suite, "key");
        String suiteName = requireText(suite, "name");
        requireText(suite, "externalId");

        JsonNode run = body.path("checkRun");
        String key = requireText(run, "key");
        String status = requireText(run, "status");
        String conclusion = run.path("conclusion").asText(null);
        if ("completed".equals(status) && (conclusion == null || conclusion.isBlank())) {
            throw new HaltException(400, "conclusion is required when status is completed");
        }
        if (!"completed".equals(status) && conclusion != null && !conclusion.isBlank()) {
            throw new HaltException(400, "conclusion is only allowed when status is completed");
        }
        OffsetDateTime externalUpdatedAt = OffsetDateTime.parse(requireText(run, "externalUpdatedAt"));
        requireText(run, "externalId");

        MockCheckRun checkRun = upsertCheckRun(repo, headSha, suiteKey, suiteName, key);
        if (checkRun.externalUpdatedAt != null && externalUpdatedAt.isBefore(checkRun.externalUpdatedAt)) {
            // A stale report must not overwrite newer state; the stored state is returned unchanged.
            MockCheckRun unchanged = checkRun;
            sendJson(he, 200, gen -> {
                gen.writeStartObject();
                writeCheckRunObject(gen, unchanged);
                gen.writeEndObject();
            });
            return;
        }
        checkRun.externalUpdatedAt = externalUpdatedAt;
        checkRun.name = requireText(run, "name");
        checkRun.status = status;
        checkRun.conclusion = conclusion;
        checkRun.detailsUrl = run.path("detailsUrl").asText(null);
        checkRun.externalId = run.path("externalId").asText(null);
        checkRun.startedAt = run.path("startedAt").asText(null);
        checkRun.completedAt = run.path("completedAt").asText(null);
        JsonNode output = run.path("output");
        checkRun.outputTitle = output.path("title").asText(null);
        checkRun.outputSummary = output.path("summary").asText(null);
        checkRun.outputText = output.path("text").asText(null);
        checkRun.reportedStates.add(conclusion == null ? status : status + "/" + conclusion);

        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeObjectFieldStart("checkSuite");
            gen.writeStringField("id", "crg_" + suiteKey.hashCode());
            gen.writeStringField("key", suiteKey);
            gen.writeStringField("name", suiteName);
            gen.writeStringField("sha", headSha);
            gen.writeEndObject();
            writeCheckRunObject(gen, checkRun);
            gen.writeEndObject();
        });
    }

    /** Cursor Origin matches a repeated report on {@code (head SHA, suite key, check key)}. */
    private MockCheckRun upsertCheckRun(MockRepo repo, String headSha, String suiteKey, String suiteName, String key) {
        for (MockCheckRun existing : repo.checkRuns) {
            if (existing.headSha.equals(headSha) && existing.suiteKey.equals(suiteKey) && existing.key.equals(key)) {
                return existing;
            }
        }
        MockCheckRun created = new MockCheckRun("cr_" + (repo.checkRuns.size() + 1), headSha, suiteKey, suiteName, key);
        repo.checkRuns.add(created);
        return created;
    }

    /**
     * Appends annotations to a check run, enforcing the batch size and per-run cap so that a
     * publisher that ignores them fails the test rather than the production API.
     */
    private void handleCreateCheckRunAnnotations(HttpExchange he, MockRepo repo, String checkRunId) throws IOException {
        MockCheckRun checkRun = repo.checkRuns.stream()
                .filter(run -> run.id.equals(checkRunId))
                .findFirst()
                .orElseThrow(() -> new HaltException(404, "check run not found: " + checkRunId));

        if (annotationFailureStatus != null) {
            throw new HaltException(annotationFailureStatus, "annotations rejected by test configuration");
        }

        JsonNode annotations = requestBody(he).path("annotations");
        if (!annotations.isArray()
                || annotations.size() < MIN_ANNOTATION_BATCH
                || annotations.size() > MAX_ANNOTATION_BATCH) {
            throw new HaltException(
                    400,
                    "annotations must contain " + MIN_ANNOTATION_BATCH + " to " + MAX_ANNOTATION_BATCH + " entries");
        }
        if (checkRun.annotations.size() + annotations.size() > MAX_ANNOTATIONS_PER_CHECK_RUN) {
            throw new HaltException(
                    429, "a check run stores at most " + MAX_ANNOTATIONS_PER_CHECK_RUN + " annotations");
        }

        List<MockAnnotation> created = new ArrayList<>();
        for (JsonNode annotation : annotations) {
            JsonNode location = annotation.path("location");
            created.add(new MockAnnotation(
                    requireText(annotation, "annotationLevel"),
                    requireText(annotation, "message"),
                    annotation.path("title").asText(null),
                    location.path("path").asText(null),
                    location.has("startLine") ? location.get("startLine").asInt() : null,
                    location.has("endLine") ? location.get("endLine").asInt() : null));
        }
        checkRun.annotations.addAll(created);

        sendJson(he, 200, gen -> {
            gen.writeStartObject();
            gen.writeArrayFieldStart("annotations");
            for (int i = 0; i < created.size(); i++) {
                MockAnnotation annotation = created.get(i);
                gen.writeStartObject();
                gen.writeStringField("id", "cra_" + i);
                gen.writeStringField("checkRunId", checkRun.id);
                gen.writeStringField("annotationLevel", annotation.level());
                gen.writeStringField("message", annotation.message());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        });
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

    private void writeCheckRunObject(JsonGenerator gen, MockCheckRun checkRun) throws IOException {
        gen.writeObjectFieldStart("checkRun");
        gen.writeStringField("id", checkRun.id);
        gen.writeStringField("sha", checkRun.headSha);
        gen.writeStringField("key", checkRun.key);
        gen.writeStringField("name", checkRun.name);
        gen.writeStringField("status", checkRun.status);
        if (checkRun.conclusion != null) {
            gen.writeStringField("conclusion", checkRun.conclusion);
        }
        if (checkRun.detailsUrl != null) {
            gen.writeStringField("detailsUrl", checkRun.detailsUrl);
        }
        if (checkRun.externalId != null) {
            gen.writeStringField("externalId", checkRun.externalId);
        }
        gen.writeEndObject();
    }

    private void writeRepoObject(JsonGenerator gen, MockRepo repo) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("name", repo.name);
        gen.writeStringField("fullName", repo.owner + "/" + repo.name);
        gen.writeStringField("defaultBranch", repo.defaultBranch);
        gen.writeStringField("cloneUrl", "https://origin.cursor.com/" + repo.owner + "/" + repo.name + ".git");
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

    /**
     * Consumes the request body and stashes it on the exchange, so that handlers can read it after
     * the filter has drained the stream.
     */
    private static void captureBody(HttpExchange he) {
        try (InputStream is = he.getRequestBody()) {
            he.setAttribute(BODY_ATTRIBUTE, is.readAllBytes());
        } catch (IOException ignored) {
            he.setAttribute(BODY_ATTRIBUTE, new byte[0]);
        }
    }

    private static JsonNode requestBody(HttpExchange he) {
        byte[] body = (byte[]) he.getAttribute(BODY_ATTRIBUTE);
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (IOException e) {
            throw new HaltException(400, "malformed JSON request body");
        }
    }

    /** Reads a required string field, halting with 400 when it is missing or blank. */
    private static String requireText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new HaltException(400, "missing required field: " + field);
        }
        return value;
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
            captureBody(he);
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
