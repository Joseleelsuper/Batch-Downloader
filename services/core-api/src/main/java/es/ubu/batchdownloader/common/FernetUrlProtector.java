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
 * Protege URLs con un formato Fernet compatible con Scraper, autenticando el contenido antes de
 * descifrarlo y admitiendo URLs históricas en claro.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public class FernetUrlProtector {
    /**
     * Valor compartido que fija v e r s i o n para el comportamiento del componente.
     */
    private static final int VERSION = 0x80;
    /**
     * Valor compartido que fija t i m e s t a m p  b y t e s para el comportamiento del componente.
     */
    private static final int TIMESTAMP_BYTES = 8;
    /**
     * Valor compartido que fija i v  b y t e s para el comportamiento del componente.
     */
    private static final int IV_BYTES = 16;
    /**
     * Valor compartido que fija h m a c  b y t e s para el comportamiento del componente.
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
     * Divide el SHA-256 del secreto compartido en una clave de firma HMAC y otra de cifrado AES de
     * 16 bytes cada una.
     *
     * @param secret Secreto compartido con Scraper del que SHA-256 deriva claves de firma y
     *     cifrado.
     */
    public FernetUrlProtector(String secret) {
        byte[] digest = sha256(secret);
        this.signingKey = Arrays.copyOfRange(digest, 0, 16);
        this.encryptionKey = Arrays.copyOfRange(digest, 16, 32);
    }

    /**
     * Conserva URLs HTTP o HTTPS históricas y, para tokens, comprueba formato y HMAC antes de
     * descifrar; no aplica caducidad a su fecha interna.
     *
     * @param value Valor que se normaliza o comprueba según el contrato del método.
     * @return URL descifrada o original; null para ausencia, token inválido o fallo criptográfico.
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
     * Cifra UTF-8 con AES-CBC e IV aleatorio, añade versión y fecha y autentica el conjunto con
     * HMAC-SHA256 antes de codificarlo en Base64 URL.
     *
     * @param value Valor que se normaliza o comprueba según el contrato del método.
     * @return token Fernet autenticado.
     * @throws IllegalStateException si el proveedor criptográfico no puede cifrar o autenticar.
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
     * Inicializa AES-CBC con PKCS5Padding y la clave derivada del secreto compartido.
     *
     * @param mode Modo Cipher.ENCRYPT_MODE o Cipher.DECRYPT_MODE de la operación.
     * @param iv Vector de inicialización de 16 bytes que acompaña al texto cifrado.
     * @return cifrador listo para el modo solicitado.
     * @throws java.security.GeneralSecurityException si clave, IV o proveedor criptográfico son
     *     incompatibles.
     */
    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    /**
     * Autentica los bytes completos del token previos a su firma con HMAC-SHA256.
     *
     * @param payload Bytes de versión, fecha, IV y contenido cifrado que se autentican
     *     conjuntamente.
     * @return firma de 32 bytes.
     * @throws java.security.GeneralSecurityException si no puede inicializarse el algoritmo o su
     *     clave.
     */
    private byte[] hmac(byte[] payload) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return mac.doFinal(payload);
    }

    /**
     * Deriva 32 bytes de material de clave a partir del secreto en UTF-8.
     *
     * @param value Valor que se normaliza o comprueba según el contrato del método.
     * @return huella SHA-256.
     * @throws IllegalStateException si el proveedor no dispone de SHA-256.
     */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
