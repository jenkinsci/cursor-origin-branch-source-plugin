package io.jenkins.plugins.cursor_origin_branch_source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for webhook signature verification — no Jenkins required. */
class OriginWebhookVerifierTest {

    private KeyPair keyPair;
    private OriginWebhookVerifier.JwksFetcher validFetcher;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        keyPair = gen.generateKeyPair();
        validFetcher = () -> List.of(keyPair.getPublic());
    }

    @Test
    void validSignaturePasses() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        assertTrue(OriginWebhookVerifier.verify(body, headers, validFetcher));
    }

    @Test
    void staleTimestampFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        long old = Instant.now().getEpochSecond() - 400;
        Map<String, String> headers = signedHeaders("whd_abc", old, body, keyPair);
        assertFalse(OriginWebhookVerifier.verify(body, headers, validFetcher));
    }

    @Test
    void futureTimestampFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        long future = Instant.now().getEpochSecond() + 400;
        Map<String, String> headers = signedHeaders("whd_abc", future, body, keyPair);
        assertFalse(OriginWebhookVerifier.verify(body, headers, validFetcher));
    }

    @Test
    void tamperedBodyFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        byte[] tampered = "{\"test\":2}".getBytes(StandardCharsets.UTF_8);
        assertFalse(OriginWebhookVerifier.verify(tampered, headers, validFetcher));
    }

    @Test
    void wrongKeyFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair otherKey = gen.generateKeyPair();
        assertFalse(OriginWebhookVerifier.verify(body, headers, () -> List.of(otherKey.getPublic())));
    }

    @Test
    void missingIdHeaderFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        Map<String, String> noId = Map.of(
                "webhook-timestamp", headers.get("webhook-timestamp"),
                "webhook-signature", headers.get("webhook-signature"));
        assertFalse(OriginWebhookVerifier.verify(body, noId, validFetcher));
    }

    @Test
    void missingSignatureHeaderFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        Map<String, String> noSig =
                Map.of("webhook-id", headers.get("webhook-id"), "webhook-timestamp", headers.get("webhook-timestamp"));
        assertFalse(OriginWebhookVerifier.verify(body, noSig, validFetcher));
    }

    @Test
    void wrongSignatureFormatFails() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        Map<String, String> headers = Map.of(
                "webhook-id", "whd_abc",
                "webhook-timestamp", String.valueOf(ts),
                "webhook-signature", "v1hmac,badsignature");
        assertFalse(OriginWebhookVerifier.verify(body, headers, validFetcher));
    }

    @Test
    void multipleKeysSecondKeyMatches() throws Exception {
        byte[] body = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders("whd_abc", Instant.now().getEpochSecond(), body, keyPair);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair decoyKey = gen.generateKeyPair();
        // fetcher returns decoy first, then the real key
        assertTrue(
                OriginWebhookVerifier.verify(body, headers, () -> List.of(decoyKey.getPublic(), keyPair.getPublic())));
    }

    /** Signs a body the same way the mock server does and returns the required headers. */
    static Map<String, String> signedHeaders(String id, long ts, byte[] body, KeyPair kp) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((id + "." + ts + ".").getBytes(StandardCharsets.UTF_8));
        md.update(body);
        String digestHex = HexFormat.of().formatHex(md.digest());

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(digestHex.getBytes(StandardCharsets.UTF_8));
        String sig64 = Base64.getEncoder().encodeToString(signer.sign());

        return Map.of("webhook-id", id, "webhook-timestamp", String.valueOf(ts), "webhook-signature", "v1ed," + sig64);
    }
}
