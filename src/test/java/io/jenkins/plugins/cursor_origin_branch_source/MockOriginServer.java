package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    record MockBranch(String name, String sha) {}

    record MockPR(int number, String headBranch, String headSha, String baseBranch, String baseSha) {}

    static class MockRepo {
        final String owner;
        final String name;
        final String defaultBranch;
        final List<MockBranch> branches = new ArrayList<>();
        final List<MockPR> pullRequests = new ArrayList<>();
        /** Paths that exist as regular files (e.g. "Jenkinsfile"). */
        final Set<String> files = ConcurrentHashMap.newKeySet();

        MockRepo(String owner, String name, String defaultBranch) {
            this.owner = owner;
            this.name = name;
            this.defaultBranch = defaultBranch;
        }

        MockRepo branch(String branchName, String sha) {
            branches.add(new MockBranch(branchName, sha));
            return this;
        }

        MockRepo pr(int number, String headBranch, String headSha, String baseBranch, String baseSha) {
            pullRequests.add(new MockPR(number, headBranch, headSha, baseBranch, baseSha));
            return this;
        }

        MockRepo file(String path) {
            files.add(path);
            return this;
        }
    }

    // ── server state ────────────────────────────────────────────────────────

    /** owner → repoName → repo */
    private final Map<String, Map<String, MockRepo>> repos = new HashMap<>();

    /** appId → public key used to verify incoming app-JWTs */
    private final Map<String, PublicKey> appPublicKeys = new ConcurrentHashMap<>();

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

    // ── builder API ─────────────────────────────────────────────────────────

    /** Register an app's public key so that JWTs it signs will be accepted. */
    MockOriginServer registerApp(String appId, PublicKey publicKey) {
        appPublicKeys.put(appId, publicKey);
        return this;
    }

    /** Add a mock repo. Use the returned {@link MockRepo} to populate branches, PRs, files. */
    MockRepo addRepo(@NonNull String owner, @NonNull String name, @NonNull String defaultBranch) {
        MockRepo repo = new MockRepo(owner, name, defaultBranch);
        repos.computeIfAbsent(owner, k -> new HashMap<>()).put(name, repo);
        return repo;
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

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
                gen.writeStringField("name", b.name());
                gen.writeObjectFieldStart("commit");
                gen.writeStringField("sha", b.sha());
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

    private void handleGetContents(HttpExchange he, MockRepo repo) throws IOException {
        String path = queryParam(he, "path");
        if (path == null || path.isBlank()) {
            // root directory listing
            sendJson(he, 200, gen -> {
                gen.writeStartObject();
                gen.writeStringField("type", "dir");
                gen.writeStringField("name", "");
                gen.writeStringField("path", "");
                gen.writeArrayFieldStart("entries");
                for (String f : repo.files) {
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
        if (repo.files.contains(path)) {
            sendJson(he, 200, gen -> {
                gen.writeStartObject();
                gen.writeStringField("type", "file");
                gen.writeStringField("name", path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
                gen.writeStringField("path", path);
                gen.writeStringField("encoding", "base64");
                gen.writeStringField("content", "");
                gen.writeEndObject();
            });
        } else {
            sendError(he, 404, "path not found: " + path);
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    @FunctionalInterface
    interface JsonWriter {
        void write(JsonGenerator gen) throws IOException;
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

    private static void drainBody(HttpExchange he) {
        try (InputStream is = he.getRequestBody()) {
            is.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
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
            drainBody(he);
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
