package io.jenkins.plugins.cursor_origin_branch_source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

class OriginWebhookVerifier {

    private static final Logger LOGGER = Logger.getLogger(OriginWebhookVerifier.class.getName());
    static final long CLOCK_SKEW_SECONDS = 300;

    /** DER prefix for Ed25519 SubjectPublicKeyInfo (12 bytes preceding the 32-byte key). */
    private static final byte[] ED25519_DER_PREFIX = {
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private static final ConcurrentHashMap<String, CachedKeys> KEY_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_SECONDS = 300;

    private record CachedKeys(Instant fetchedAt, List<PublicKey> keys) {
        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }

    @FunctionalInterface
    interface JwksFetcher {
        List<PublicKey> fetchKeys() throws Exception;
    }

    static boolean verify(byte[] rawBody, Map<String, String> headers, JwksFetcher jwksFetcher) {
        String id = headers.get("webhook-id");
        String timestampStr = headers.get("webhook-timestamp");
        String sigHeader = headers.get("webhook-signature");

        if (id == null || timestampStr == null || sigHeader == null) {
            LOGGER.fine("Missing webhook headers");
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            LOGGER.fine("Invalid webhook-timestamp: " + timestampStr);
            return false;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > CLOCK_SKEW_SECONDS) {
            LOGGER.fine(() -> "Webhook timestamp out of range: " + timestamp + " vs now=" + now);
            return false;
        }

        String sig64 = null;
        for (String part : sigHeader.split("\\s+")) {
            if (part.startsWith("v1ed,")) {
                sig64 = part.substring(5);
                break;
            }
        }
        if (sig64 == null) {
            LOGGER.fine("No v1ed signature in: " + sigHeader);
            return false;
        }

        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(sig64);
        } catch (IllegalArgumentException e) {
            LOGGER.fine("Invalid base64 in webhook signature");
            return false;
        }

        byte[] digestUtf8;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((id + "." + timestampStr + ".").getBytes(StandardCharsets.UTF_8));
            md.update(rawBody);
            digestUtf8 = HexFormat.of().formatHex(md.digest()).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to compute SHA-256 digest", e);
            return false;
        }

        try {
            List<PublicKey> keys = jwksFetcher.fetchKeys();
            for (PublicKey key : keys) {
                try {
                    Signature sig = Signature.getInstance("Ed25519");
                    sig.initVerify(key);
                    sig.update(digestUtf8);
                    if (sig.verify(sigBytes)) {
                        return true;
                    }
                } catch (Exception e) {
                    LOGGER.fine("Signature check failed with one key: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "JWKS fetch/verify error", e);
        }
        return false;
    }

    static JwksFetcher cachedLiveFetcher() {
        return () -> {
            String uri = CursorOriginAppCredentials.API_BASE_URI + "/v1/origin/keys";
            CachedKeys cached = KEY_CACHE.get(uri);
            if (cached != null && !cached.isExpired()) {
                return cached.keys();
            }
            List<PublicKey> keys = fetchJwksKeys(uri);
            KEY_CACHE.put(uri, new CachedKeys(Instant.now(), keys));
            return keys;
        };
    }

    static void clearCache() {
        KEY_CACHE.clear();
    }

    static PublicKey parseEd25519Jwk(JsonNode jwk) throws Exception {
        byte[] rawKey = Base64.getUrlDecoder().decode(jwk.path("x").asText());
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("Expected 32-byte Ed25519 key, got " + rawKey.length);
        }
        byte[] der = new byte[ED25519_DER_PREFIX.length + rawKey.length];
        System.arraycopy(ED25519_DER_PREFIX, 0, der, 0, ED25519_DER_PREFIX.length);
        System.arraycopy(rawKey, 0, der, ED25519_DER_PREFIX.length, rawKey.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static List<PublicKey> fetchJwksKeys(String uri) throws IOException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(uri)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("JWKS fetch failed: HTTP " + resp.statusCode());
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(resp.body());
            List<PublicKey> keys = new ArrayList<>();
            for (JsonNode jwk : root.path("keys")) {
                if ("OKP".equals(jwk.path("kty").asText()) && "Ed25519".equals(jwk.path("crv").asText())) {
                    try {
                        keys.add(parseEd25519Jwk(jwk));
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to parse JWK entry", e);
                    }
                }
            }
            return keys;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching JWKS", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("JWKS fetch error", e);
        }
    }
}
