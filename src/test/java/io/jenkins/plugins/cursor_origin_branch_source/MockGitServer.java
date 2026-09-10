package io.jenkins.plugins.cursor_origin_branch_source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

/**
 * Minimal Git smart-HTTP server backed by JGit file-based repositories, for use in tests.
 *
 * <p>Authenticates via HTTP Basic, expecting password {@code oit_<JWT>}. Records the
 * {@code scopes} and {@code repositoryIds} claims from each request so tests can assert that
 * tokens were properly scoped, and enforces that scoped tokens (non-empty {@code repositoryIds})
 * only grant access to the repos they name.
 */
class MockGitServer implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(MockGitServer.class.getName());
    private static final Pattern REPO_PATH = Pattern.compile("^/([^/]+)/([^/]+)\\.git(/.*)?$");

    record LastAuth(List<String> repositoryIds, List<String> scopes) {}

    private final PublicKey originPublicKey;
    /** "owner/name" → repo working directory */
    private final Map<String, File> repoDirs = new ConcurrentHashMap<>();
    /** "owner/name" → auth claims from most recent authenticated request */
    private final Map<String, LastAuth> lastAuths = new ConcurrentHashMap<>();
    /** "owner/name" → the repo ID that a scoped token must include to access this repo */
    private final Map<String, String> repoIds = new ConcurrentHashMap<>();

    private Path tempDir;
    private HttpServer server;
    private String baseUrl;

    MockGitServer(PublicKey originPublicKey) {
        this.originPublicKey = originPublicKey;
    }

    /**
     * Creates a file-based git repo with one commit on the given branch. No repo ID is registered,
     * so only unrestricted tokens (empty {@code repositoryIds}) can access it.
     *
     * @return the HEAD commit SHA
     */
    String addRepo(String owner, String name, String branchName, Map<String, String> files) throws Exception {
        return addRepo(owner, name, null, branchName, files);
    }

    /**
     * Creates a file-based git repo with one commit on the given branch and registers it under
     * {@code owner/name} with the given {@code repoId}. Scoped tokens must include this ID.
     *
     * @return the HEAD commit SHA
     */
    String addRepo(String owner, String name, String repoId, String branchName, Map<String, String> files)
            throws Exception {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("mock-git-server");
        }
        File repoDir = tempDir.resolve(owner + "_" + name).toFile();
        repoDir.mkdirs();
        try (Git git =
                Git.init().setInitialBranch(branchName).setDirectory(repoDir).call()) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                File f = new File(repoDir, entry.getKey());
                f.getParentFile().mkdirs();
                Files.writeString(f.toPath(), entry.getValue());
                git.add().addFilepattern(entry.getKey()).call();
            }
            git.commit()
                    .setAuthor("Test", "test@example.com")
                    .setMessage("initial")
                    .setSign(false)
                    .call();
            repoDirs.put(owner + "/" + name, repoDir);
            if (repoId != null) {
                repoIds.put(owner + "/" + name, repoId);
            }
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", he -> {
            try {
                handle(he);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error handling git request", e);
                sendStatus(he, 500);
            } finally {
                he.close();
            }
        });
        server.start();
        InetSocketAddress addr = server.getAddress();
        baseUrl = "http://" + addr.getHostString() + ":" + addr.getPort();
        LOGGER.info("MockGitServer started at " + baseUrl);
        return baseUrl;
    }

    String baseUrl() {
        return baseUrl;
    }

    LastAuth getLastAuth(String owner, String name) {
        return lastAuths.get(owner + "/" + name);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(HttpExchange he) throws Exception {
        String path = he.getRequestURI().getPath();
        Matcher m = REPO_PATH.matcher(path);
        if (!m.matches()) {
            sendStatus(he, 404);
            return;
        }
        String owner = m.group(1);
        String name = m.group(2);
        String rest = m.group(3); // "/info/refs", "/git-upload-pack", or null

        LastAuth auth = authenticate(he, owner, name);
        if (auth == null) {
            return; // error already sent
        }
        lastAuths.put(owner + "/" + name, auth);

        File repoDir = repoDirs.get(owner + "/" + name);
        if (repoDir == null) {
            sendStatus(he, 404);
            return;
        }

        try (Repository repo = Git.open(repoDir).getRepository()) {
            if ("/info/refs".equals(rest) && "GET".equals(he.getRequestMethod())) {
                String service = queryParam(he, "service");
                if ("git-upload-pack".equals(service)) {
                    if (!checkScopes(he, auth, false)) return;
                    he.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-advertisement");
                    he.getResponseHeaders().set("Cache-Control", "no-cache");
                    he.sendResponseHeaders(200, 0);
                    try (OutputStream out = he.getResponseBody()) {
                        PacketLineOut pktOut = new PacketLineOut(out);
                        pktOut.writeString("# service=git-upload-pack\n");
                        pktOut.end();
                        UploadPack up = new UploadPack(repo);
                        up.setBiDirectionalPipe(false);
                        up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(pktOut));
                    }
                } else if ("git-receive-pack".equals(service)) {
                    if (!checkScopes(he, auth, true)) return;
                    he.getResponseHeaders().set("Content-Type", "application/x-git-receive-pack-advertisement");
                    he.getResponseHeaders().set("Cache-Control", "no-cache");
                    he.sendResponseHeaders(200, 0);
                    try (OutputStream out = he.getResponseBody()) {
                        PacketLineOut pktOut = new PacketLineOut(out);
                        pktOut.writeString("# service=git-receive-pack\n");
                        pktOut.end();
                        ReceivePack rp = new ReceivePack(repo);
                        rp.setBiDirectionalPipe(false);
                        rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(pktOut));
                    }
                } else {
                    sendStatus(he, 403);
                }
            } else if ("/git-upload-pack".equals(rest) && "POST".equals(he.getRequestMethod())) {
                if (!checkScopes(he, auth, false)) return;
                he.getResponseHeaders().set("Content-Type", "application/x-git-upload-pack-result");
                he.getResponseHeaders().set("Cache-Control", "no-cache");
                he.sendResponseHeaders(200, 0);
                UploadPack up = new UploadPack(repo);
                up.setBiDirectionalPipe(false);
                try (OutputStream out = he.getResponseBody()) {
                    up.upload(he.getRequestBody(), out, null);
                }
            } else if ("/git-receive-pack".equals(rest) && "POST".equals(he.getRequestMethod())) {
                if (!checkScopes(he, auth, true)) return;
                he.getResponseHeaders().set("Content-Type", "application/x-git-receive-pack-result");
                he.getResponseHeaders().set("Cache-Control", "no-cache");
                he.sendResponseHeaders(200, 0);
                ReceivePack rp = new ReceivePack(repo);
                rp.setBiDirectionalPipe(false);
                try (OutputStream out = he.getResponseBody()) {
                    rp.receive(he.getRequestBody(), out, null);
                }
            } else {
                sendStatus(he, 404);
            }
        }
    }

    /**
     * Checks that a scoped token (non-empty {@code scopes} claim) permits the requested operation.
     * Unrestricted tokens (empty scopes) are always allowed.
     *
     * <p>Per the Origin API: {@code repository:metadata:read} is always present; read operations
     * additionally require {@code repository:contents:read}; write operations require
     * {@code repository:contents:write} (which implicitly grants {@code :read}).
     *
     * @param write {@code true} for push (receive-pack), {@code false} for fetch (upload-pack)
     * @return {@code true} if access is permitted; {@code false} if a 403 was already sent
     */
    private boolean checkScopes(HttpExchange he, LastAuth auth, boolean write) throws IOException {
        List<String> scopes = auth.scopes();
        if (scopes.isEmpty()) return true; // unrestricted token: allow all operations
        if (!scopes.contains("repository:metadata:read")) {
            LOGGER.warning("Token scopes " + scopes + " missing repository:metadata:read");
            sendStatus(he, 403);
            return false;
        }
        if (write) {
            if (!scopes.contains("repository:contents:write")) {
                LOGGER.warning("Token scopes " + scopes + " missing repository:contents:write for push");
                sendStatus(he, 403);
                return false;
            }
        } else {
            // contents:write implicitly grants contents:read
            if (!scopes.contains("repository:contents:read") && !scopes.contains("repository:contents:write")) {
                LOGGER.warning("Token scopes " + scopes + " missing repository:contents:read for fetch");
                sendStatus(he, 403);
                return false;
            }
        }
        return true;
    }

    /**
     * Parses Basic auth, requires the {@code oit_} prefix, verifies the JWT against the Origin
     * server's public key, enforces repository ID scoping, and records the claims as
     * {@link LastAuth}.
     *
     * @return the auth record, or {@code null} if authentication or authorization failed
     */
    private LastAuth authenticate(HttpExchange he, String owner, String name) throws IOException {
        String header = he.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            he.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"git\"");
            sendStatus(he, 401);
            return null;
        }
        String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.ISO_8859_1);
        int colon = decoded.indexOf(':');
        String password = colon >= 0 ? decoded.substring(colon + 1) : decoded;
        if (!password.startsWith("oit_")) {
            LOGGER.warning("Git auth to " + owner + "/" + name + ": token does not start with oit_");
            he.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"git\"");
            sendStatus(he, 401);
            return null;
        }
        String jwtPart = password.substring(4);
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(originPublicKey).build().parseSignedClaims(jwtPart);
            Claims claims = jws.getPayload();
            @SuppressWarnings("unchecked")
            List<String> tokenRepoIds = (List<String>) claims.get("repositoryIds");
            @SuppressWarnings("unchecked")
            List<String> scopes = (List<String>) claims.get("scopes");
            List<String> effectiveRepoIds = tokenRepoIds != null ? List.copyOf(tokenRepoIds) : List.of();
            // Enforce scoping: if token names specific repos, this repo must be among them;
            // a repo without a registered ID is also inaccessible to a scoped token
            String registeredId = repoIds.get(owner + "/" + name);
            if (!effectiveRepoIds.isEmpty() && (registeredId == null || !effectiveRepoIds.contains(registeredId))) {
                LOGGER.warning("Token repositoryIds " + effectiveRepoIds + " does not permit access to " + owner + "/"
                        + name + " (id=" + registeredId + ")");
                sendStatus(he, 403);
                return null;
            }
            return new LastAuth(effectiveRepoIds, scopes != null ? List.copyOf(scopes) : List.of());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "JWT verification failed for git auth to " + owner + "/" + name, e);
            he.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"git\"");
            sendStatus(he, 401);
            return null;
        }
    }

    private static void sendStatus(HttpExchange he, int status) throws IOException {
        he.sendResponseHeaders(status, -1);
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
}
