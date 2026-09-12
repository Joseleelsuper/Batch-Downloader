package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deriva hashes HMAC para reconocer un navegador y aplicar cuotas de red sin persistir cookie o IP
 * en claro.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see DownloadRequestOwner.RequestOwner
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Component
public class DownloadRequestOwner {
    /**
     * Valor compartido que fija h m a c  a l g o r i t h m para el comportamiento del componente.
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Estado {@code secret} mantenido por {@code DownloadRequestOwner}.
     */
    private final byte[] secret;

    /**
     * Carga la clave HMAC compartida entre resolución de propietarios y cuotas anónimas.
     *
     * @param anonymousOwnerSecret Secreto HMAC configurado para derivar identidades anónimas sin
     *     guardar el token ni la IP.
     *
     * @throws IllegalStateException si el secreto configurado está vacío.
     */
    public DownloadRequestOwner(
            @Value("${app.download.anonymous-owner-secret}") String anonymousOwnerSecret) {
        if (anonymousOwnerSecret == null || anonymousOwnerSecret.isBlank()) {
            throw new IllegalStateException("app.download.anonymous-owner-secret must be configured");
        }
        this.secret = anonymousOwnerSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Combina la cuenta autenticada con hashes opcionales de cookie y dirección IP; la IP usa el
     * prefijo ip: para separar su propósito.
     *
     * @param userId UUID de la cuenta autenticada o null si no hay sesión de usuario.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     *
     * @param remoteAddress Dirección de red observada por Core, o null si no está disponible.
     * @return identidad de acceso y cuotas sin incluir el token ni la dirección original.
     */
    public RequestOwner resolve(UUID userId, String browserToken, String remoteAddress) {
        String browserHash = browserToken == null || browserToken.isBlank() ? null : hash(browserToken);
        String ipHash = remoteAddress == null || remoteAddress.isBlank() ? null : hash("ip:" + remoteAddress);
        return new RequestOwner(userId, browserHash, ipHash);
    }

    /**
     * Calcula HMAC-SHA256 con el secreto del servicio para derivar una identidad opaca.
     *
     * @param value Token de navegador o dirección prefijada; no debe incluirse en logs.
     * @return digest hexadecimal del valor.
     * @throws IllegalStateException si el proveedor criptográfico no puede calcular el HMAC.
     */
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("anonymous_owner_hash_failed", exception);
        }
    }

    /**
     * Agrupa la cuenta opcional y las identidades anónimas necesarias para comprobar propiedad y
     * cuotas.
     *
     * @param userId UUID de la cuenta autenticada o null si no hay sesión de usuario.
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record RequestOwner(UUID userId, String anonymousOwnerHash, String anonymousIpHash) {
        /**
         * Indica si la solicitud aporta una identidad de cuenta.
         *
         * @return true cuando userId no es null.
         */
        public boolean authenticated() {
            return userId != null;
        }

        /**
         * Acepta acceso por coincidencia de cuenta o del navegador original del trabajo.
         *
         * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
         * @param jobAnonymousOwnerHash Hash del navegador propietario persistido en el trabajo.
         * @return true si coincide al menos una de las identidades disponibles.
         */
        public boolean canAccess(UUID ownerId, String jobAnonymousOwnerHash) {
            return (userId != null && userId.equals(ownerId))
                    || (anonymousOwnerHash != null && anonymousOwnerHash.equals(jobAnonymousOwnerHash));
        }

        /**
         * Exige la identidad del navegador antes de consultar cuotas o acceder a trabajos anónimos.
         *
         * @return hash no vacío del navegador.
         * @throws es.ubu.batchdownloader.common.NotFoundException si no existe un hash de
         *     propietario anónimo.
         */
        public String requireAnonymousOwnerHash() {
            if (anonymousOwnerHash == null || anonymousOwnerHash.isBlank()) {
                throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
            }
            return anonymousOwnerHash;
        }
    }
}
