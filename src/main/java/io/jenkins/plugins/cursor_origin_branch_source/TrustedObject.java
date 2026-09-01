package io.jenkins.plugins.cursor_origin_branch_source;

import com.google.common.annotations.VisibleForTesting;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import jenkins.security.SlaveToMasterCallable;
import jenkins.util.JenkinsJVM;

// TODO consider for inclusion in jenkins.security

/**
 * A signed object which may be passed to an agent and back and then used safely from the controller.
 * Any attempt by code running in the agent to construct malicious data will be rejected;
 * the data must have been constructed originally in the controller in the same session.
 * Suitable for use as a field in a {@link SlaveToMasterCallable}.
 */
public final class TrustedObject<T extends Serializable> implements Serializable {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Mac MAC;

    static {
        if (JenkinsJVM.isJenkinsJVM()) {
            try {
                MAC = Mac.getInstance(ALGORITHM);
                MAC.init(KeyGenerator.getInstance(ALGORITHM).generateKey());
            } catch (GeneralSecurityException x) {
                throw new AssertionError(x);
            }
        } else {
            MAC = null;
        }
    }

    private static final long serialVersionUID = 1;

    private transient T o;
    private final byte[] ser;

    @VisibleForTesting
    final byte[] mac;

    /**
     * Create a trusted object wrapper inside the controller.
     */
    public TrustedObject(T o) {
        JenkinsJVM.checkJenkinsJVM();
        this.o = o;
        ser = serialize(o);
        mac = hash(ser);
    }

    @VisibleForTesting
    TrustedObject(T o, byte[] ser, byte[] mac) {
        this.o = o;
        this.ser = ser;
        this.mac = mac;
    }

    /**
     * The wrapped object.
     */
    public T o() {
        return o;
    }

    /**
     * Validates that the wrapper was in fact signed by the controller.
     */
    @SuppressWarnings("unchecked")
    private Object readResolve() {
        if (JenkinsJVM.isJenkinsJVM() && !MessageDigest.isEqual(mac, hash(ser))) {
            throw new SecurityException("Incorrect HMAC");
        }
        o = (T) deserialize(ser);
        return this;
    }

    private static synchronized byte[] hash(byte[] ser) {
        return MAC.doFinal(ser);
    }

    @VisibleForTesting
    static byte[] serialize(Serializable o) {
        try (var baos = new ByteArrayOutputStream();
                var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(o);
            oos.flush();
            return baos.toByteArray();
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
    }

    private static Object deserialize(byte[] ser) {
        try (var bais = new ByteArrayInputStream(ser);
                var ois = new ObjectInputStream(bais)) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException x) {
            throw new RuntimeException(x);
        }
    }
}
