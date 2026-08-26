package es.ubu.batchdownloader.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implementa el componente {@code FernetUrlProtector}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class FernetUrlProtector {
    /**
     * Constante que define {@code VERSION}.
     */
    private static final int VERSION = 0x80;
    /**
     * Constante que define {@code TIMESTAMP_BYTES}.
     */
    private static final int TIMESTAMP_BYTES = 8;
    /**
     * Constante que define {@code IV_BYTES}.
     */
    private static final int IV_BYTES = 16;
    /**
     * Constante que define {@code HMAC_BYTES}.
     */
    private static final int HMAC_BYTES = 32;

    /**
     * Estado {@code signingKey} mantenido por {@code FernetUrlProtector}.
     */
    private final byte[] signingKey;
    /**
     * Estado {@code encryptionKey} mantenido por {@code FernetUrlProtector}.
     */
    private final byte[] encryptionKey;
    /**
     * Estado {@code secureRandom} mantenido por {@code FernetUrlProtector}.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Inicializa una instancia de {@code FernetUrlProtector}.
     *
     * @param secret Valor de {@code secret} utilizado por la operación.
     */
    public FernetUrlProtector(String secret) {
        byte[] digest = sha256(secret);
        this.signingKey = Arrays.copyOfRange(digest, 0, 16);
        this.encryptionKey = Arrays.copyOfRange(digest, 16, 32);
    }

    /**
     * Ejecuta la operación {@code reveal}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code reveal}.
     */
    public String reveal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        try {
            byte[] token = Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.UTF_8));
            if (token.length < 1 + TIMESTAMP_BYTES + IV_BYTES + HMAC_BYTES + 1 || (token[0] & 0xff) != VERSION) {
                return null;
            }
            int hmacOffset = token.length - HMAC_BYTES;
            byte[] signedPayload = Arrays.copyOfRange(token, 0, hmacOffset);
            byte[] expectedHmac = hmac(signedPayload);
            byte[] actualHmac = Arrays.copyOfRange(token, hmacOffset, token.length);
            if (!MessageDigest.isEqual(expectedHmac, actualHmac)) {
                return null;
            }

            byte[] iv = Arrays.copyOfRange(token, 1 + TIMESTAMP_BYTES, 1 + TIMESTAMP_BYTES + IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(token, 1 + TIMESTAMP_BYTES + IV_BYTES, hmacOffset);
            return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return null;
        }
    }

    /**
     * Ejecuta la operación {@code protect}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code protect}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    public String protect(String value) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(1 + TIMESTAMP_BYTES + IV_BYTES + ciphertext.length);
            payload.put((byte) VERSION);
            payload.putLong(Instant.now().getEpochSecond());
            payload.put(iv);
            payload.put(ciphertext);
            byte[] signedPayload = payload.array();
            ByteBuffer token = ByteBuffer.allocate(signedPayload.length + HMAC_BYTES);
            token.put(signedPayload);
            token.put(hmac(signedPayload));
            return Base64.getUrlEncoder().encodeToString(token.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not protect URL", exception);
        }
    }

    /**
     * Ejecuta la operación {@code cipher}.
     *
     * @param mode Valor de {@code mode} utilizado por la operación.
     * @param iv Valor de {@code iv} utilizado por la operación.
     * @return Resultado producido por {@code cipher}.
     * @throws GeneralSecurityException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    /**
     * Ejecuta la operación {@code hmac}.
     *
     * @param payload Carga de datos recibida por la operación.
     * @return Resultado producido por {@code hmac}.
     * @throws GeneralSecurityException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private byte[] hmac(byte[] payload) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    /**
     * Ejecuta la operación {@code sha256}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code sha256}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
