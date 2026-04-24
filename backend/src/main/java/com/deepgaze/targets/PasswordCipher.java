package com.deepgaze.targets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM at-rest encryption for target passwords stored in the control-plane
 * SQLite DB. Key material is read from {@code DEEPGAZE_SECRET_KEY} (env or
 * application.yml binding) and hashed with SHA-256 to a 32-byte AES-256 key,
 * so any key length the operator supplies works.
 *
 * Ciphertext format (base64-encoded on the wire):
 *   [12 byte IV][ciphertext || 16 byte GCM tag]
 *
 * When the key is absent, the cipher throws on every encrypt/decrypt so an
 * operator cannot accidentally write plaintext passwords to disk. The table
 * stores the base64 blob with an {@code enc:v1:} prefix; rows without the
 * prefix are treated as plaintext (for a clean upgrade path from a future
 * migration that didn't encrypt).
 */
@Slf4j
@Component
public class PasswordCipher {

    public static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom rng = new SecureRandom();

    public PasswordCipher(@Value("${deepgaze.security.encryption-key:}") String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            this.key = null;
            log.warn("DEEPGAZE_SECRET_KEY not set — target password encryption disabled. " +
                    "Writing a target with a non-empty password will be rejected until the key is configured.");
        } else {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] digest = sha.digest(rawKey.getBytes(StandardCharsets.UTF_8));
                this.key = new SecretKeySpec(digest, "AES");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialise PasswordCipher", e);
            }
        }
    }

    public boolean isConfigured() { return key != null; }

    /** Returns the cipher-text with the {@value #PREFIX} prefix. Empty strings pass through unchanged. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        if (key == null) {
            throw new IllegalStateException(
                    "DEEPGAZE_SECRET_KEY is not configured — cannot encrypt target password. " +
                    "Set the env var (or deepgaze.security.encryption-key) and restart.");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("Password encryption failed", e);
        }
    }

    /**
     * Decrypts ciphertext produced by {@link #encrypt(String)}. A blank input
     * returns blank. A stored value without the {@value #PREFIX} prefix is
     * treated as legacy plaintext so a partly-migrated DB keeps working.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;          // legacy plaintext
        if (key == null) {
            throw new IllegalStateException(
                    "DEEPGAZE_SECRET_KEY is not configured — cannot decrypt stored target passwords.");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (packed.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            System.arraycopy(packed, IV_BYTES, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Password decryption failed — wrong DEEPGAZE_SECRET_KEY?", e);
        }
    }
}
