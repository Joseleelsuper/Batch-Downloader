package es.ubu.batchdownloader.contracts.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Intercambia tokens de identidad cifrados y autenticados entre el productor Core y el consumidor
 * de correo.
 *
 * El formato enc:v2 combina un nonce de 12 bytes con AES-256-GCM y autentica destinatario y finalidad.
 * El productor genera un nonce nuevo por cifrado; el consumidor rechaza sobres alterados,
 * versiones no admitidas y claves incompatibles antes de utilizar el token.
 *
 * @see javax.crypto.Cipher
 * @see java.security.SecureRandom
 * @since 0.2.0-SNAPSHOT
 * @version 0.2.0-SNAPSHOT
 * @category Contratos compartidos
 */
public final class NotificationTokenEnvelope {
    public static final String VERSION_PREFIX = "enc:v2:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD =
            "batch-downloader.notification-token.v2".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec key;
    private final SecureRandom random;

    /**
     * Decodifica y valida la clave compartida y prepara la fuente criptográfica de nonces.
     *
     * @param encodedKey Clave AES de 32 bytes codificada en Base64; debe coincidir en productor y
     *     consumidor.
     *
     * @throws IllegalStateException si la clave no es Base64 válido o no contiene exactamente 32
     *     bytes.
     */
    public NotificationTokenEnvelope(String encodedKey) {
        this(encodedKey, new SecureRandom());
    }

    /**
     * Decodifica y valida la clave compartida y prepara la fuente criptográfica de nonces.
     *
     * @param encodedKey Clave AES de 32 bytes codificada en Base64; debe coincidir en productor y
     *     consumidor.
     *
     * @param random Generador criptográfico de nonces; inyectable para controlar vectores de
     *     prueba.
     *
     * @throws IllegalStateException si la clave no es Base64 válido o no contiene exactamente 32
     *     bytes.
     */
    NotificationTokenEnvelope(String encodedKey, SecureRandom random) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_encryption_key_invalid", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("notification_token_encryption_key_must_be_32_bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.random = random;
    }

    /**
     * Cifra el token en UTF-8 con autenticación y lo serializa en un sobre enc:v2 sin relleno
     * Base64.
     * La entrada pública genera el nonce; la variante interna permite verificar vectores del
     * contrato.
     *
     * @param plaintext Token de un solo uso no vacío que debe ocultarse durante su transporte y
     *     almacenamiento.
     *
     * @param recipient Destinatario autenticado junto al token; se eliminan solo espacios exteriores.
     * @param purpose Finalidad exacta de la plantilla autenticada junto al destinatario.
     * @return sobre versionado apto para JSON y transporte de eventos.
     * @throws IllegalArgumentException si el token está vacío o el nonce explícito no contiene 12
     *     bytes.
     *
     * @throws IllegalStateException si el proveedor criptográfico no puede completar el cifrado.
     * @see NotificationTokenEnvelope#decrypt(String, String, String)
     */
    public String encrypt(String plaintext, String recipient, String purpose) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return encrypt(plaintext, recipient, purpose, nonce);
    }

    /**
     * Verifica versión, longitud y autenticidad del sobre antes de revelar su token UTF-8.
     *
     * @param envelopeValue Sobre enc:v2 en Base64 URL que contiene nonce, texto cifrado y etiqueta
     *     de autenticación.
     *
     * @param recipient Destinatario esperado, que debe coincidir con el usado al cifrar.
     * @param purpose Finalidad esperada, que debe coincidir con la usada al cifrar.
     * @return token descifrado únicamente tras validar la etiqueta GCM y su contexto.
     * @throws IllegalArgumentException si el texto no comienza por enc:v2.
     * @throws IllegalStateException si Base64, longitud, clave o etiqueta de autenticación impiden
     *     descifrarlo.
     *
     * @see NotificationTokenEnvelope#encrypt(String, String, String)
     */
    public String decrypt(String envelopeValue, String recipient, String purpose) {
        if (!isVersion2(envelopeValue)) {
            throw new IllegalArgumentException("notification_token_envelope_version_required");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder()
                    .decode(envelopeValue.substring(VERSION_PREFIX.length()));
            if (envelope.length < NONCE_BYTES + TAG_BITS / Byte.SIZE + 1) {
                throw new GeneralSecurityException("invalid_envelope");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context(recipient, purpose));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_decryption_failed", exception);
        }
    }

    /**
     * Comprueba únicamente la marca enc:v2, sin validar ni descifrar el resto del contenido.
     *
     * @param value Cadena cuya marca de versión se comprueba; admite null.
     * @return true si el prefijo es reconocido; no implica que el sobre sea auténtico.
     */
    public static boolean isVersion2(String value) {
        return value != null && value.startsWith(VERSION_PREFIX);
    }

    /**
     * Cifra el token en UTF-8 con autenticación y lo serializa en un sobre enc:v2 sin relleno
     * Base64.
     * La entrada pública genera el nonce; la variante interna permite verificar vectores del
     * contrato.
     *
     * @param plaintext Token de un solo uso no vacío que debe ocultarse durante su transporte y
     *     almacenamiento.
     *
     * @param recipient Destinatario autenticado junto al token.
     * @param purpose Finalidad exacta de la plantilla.
     * @param nonce Nonce de 12 bytes que no debe reutilizarse con la misma clave; el overload
     *     público lo genera.
     *
     * @return sobre versionado apto para JSON y transporte de eventos.
     * @throws IllegalArgumentException si el token está vacío o el nonce explícito no contiene 12
     *     bytes.
     *
     * @throws IllegalStateException si el proveedor criptográfico no puede completar el cifrado.
     * @see NotificationTokenEnvelope#decrypt(String, String, String)
     */
    String encrypt(String plaintext, String recipient, String purpose, byte[] nonce) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("notification_token_required");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("notification_token_nonce_must_be_12_bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context(recipient, purpose));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return VERSION_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("notification_token_encryption_failed", exception);
        }
    }

    private static byte[] context(String recipient, String purpose) {
        if (recipient == null || recipient.isBlank() || purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("notification_token_context_required");
        }
        byte[] address = recipient.strip().getBytes(StandardCharsets.UTF_8);
        byte[] template = purpose.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(AAD.length + 8 + address.length + template.length)
                .put(AAD).putInt(address.length).put(address).putInt(template.length).put(template).array();
    }
}
