package io.jenkins.plugins.cursor_origin_branch_source;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import jenkins.security.SlaveToMasterCallable;
import jenkins.util.JenkinsJVM;

// TODO consider for inclusion in jenkins.security

/**
 * A encrypted object which may be passed to an agent and back and then used safely from the controller.
 * Any attempt by code running in the agent to construct malicious data will be rejected;
 * the data must have been constructed originally in the controller in the same session.
 * Furthermore, the agent cannot inspect the contents (beyond what it could guess based on serialized size).
 * Suitable for use as a field in a {@link SlaveToMasterCallable}.
 */
@SuppressFBWarnings(value = "DMI_RANDOM_USED_ONLY_ONCE", justification = "used once per JVM, fine")
public final class EncryptedObject<T extends Serializable> implements Serializable {

    private static final String KEY_ALGORITHM = "AES";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final SecretKey KEY;
    private static final byte[] IV_PREFIX;
    private static final AtomicLong IV_COUNTER = new AtomicLong();

    static {
        if (JenkinsJVM.isJenkinsJVM()) {
            try {
                var rng = new SecureRandom();
                var kg = KeyGenerator.getInstance(KEY_ALGORITHM);
                kg.init(KEY_SIZE_BITS, rng);
                KEY = kg.generateKey();
                IV_PREFIX = new byte[4];
                rng.nextBytes(IV_PREFIX);
            } catch (GeneralSecurityException x) {
                throw new AssertionError(x);
            }
        } else {
            KEY = null;
            IV_PREFIX = null;
        }
    }

    private static final long serialVersionUID = 1;

    private transient T o;
    private final byte[] data, iv;

    /**
     * Create an encrypted object wrapper inside the controller.
     */
    public EncryptedObject(T o) {
        JenkinsJVM.checkJenkinsJVM();
        this.o = o;
        byte[] ser;
        try {
            ser = serialize(o);
        } catch (IOException x) {
            throw new IllegalArgumentException(x);
        }
        try {
            iv = nextIV();
            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            data = cipher.doFinal(ser);
        } catch (GeneralSecurityException x) {
            throw new AssertionError(x);
        }
    }

    /**
     * The wrapped object.
     * May only be called from inside the controller.
     */
    public T o() {
        JenkinsJVM.checkJenkinsJVM();
        return o;
    }

    /**
     * Validates that the wrapper was in fact signed by the controller.
     */
    @SuppressWarnings("unchecked")
    private Object readResolve() throws ObjectStreamException {
        if (JenkinsJVM.isJenkinsJVM()) {
            try {
                var cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
                var ser = cipher.doFinal(data);
                o = (T) deserialize(ser);
            } catch (GeneralSecurityException | IOException | ClassNotFoundException x) {
                throw new InvalidObjectException(x.toString(), x);
            }
        }
        return this;
    }

    private static byte[] nextIV() {
        long count = IV_COUNTER.getAndIncrement();
        if (count < 0) {
            throw new IllegalStateException("IV counter overflow; the controller must be restarted");
        }
        var iv = new byte[12];
        System.arraycopy(IV_PREFIX, 0, iv, 0, 4);
        ByteBuffer.wrap(iv, 4, 8).putLong(count);
        return iv;
    }

    private static byte[] serialize(Serializable o) throws IOException {
        try (var baos = new ByteArrayOutputStream();
                var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(o);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private static Object deserialize(byte[] ser) throws IOException, ClassNotFoundException {
        try (var bais = new ByteArrayInputStream(ser);
                var ois = new ObjectInputStream(bais)) {
            return ois.readObject();
        }
    }
}
