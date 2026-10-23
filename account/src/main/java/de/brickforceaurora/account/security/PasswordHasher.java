package de.brickforceaurora.account.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing (JDK only, no native dependencies).
 *
 * Format: {@code pbkdf2$<iterations>$<salt b64>$<hash b64>}
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
        throw new UnsupportedOperationException();
    }

    public static String hash(final String password) {
        final byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        final byte[] hash = derive(password, salt, ITERATIONS);
        final Base64.Encoder encoder = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + '$' + encoder.encodeToString(salt) + '$' + encoder.encodeToString(hash);
    }

    public static boolean verify(final String password, final String stored) {
        if (password == null || stored == null) {
            return false;
        }
        final String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        final Base64.Decoder decoder = Base64.getDecoder();
        final byte[] expected = decoder.decode(parts[3]);
        final byte[] actual = derive(password, decoder.decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(final String password, final byte[] salt, final int iterations) {
        final PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Couldn't hash password", e);
        } finally {
            spec.clearPassword();
        }
    }

}
