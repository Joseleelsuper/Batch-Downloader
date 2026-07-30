package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code DownloadRequestOwner}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadRequestOwner {
    /**
     * Constante que define {@code HMAC_ALGORITHM}.
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Estado {@code users} mantenido por {@code DownloadRequestOwner}.
     */
    private final UserAccountStore users;
    /**
     * Estado {@code secret} mantenido por {@code DownloadRequestOwner}.
     */
    private final byte[] secret;

    /**
     * Inicializa una instancia de {@code DownloadRequestOwner}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @param anonymousOwnerSecret Valor de {@code anonymousOwnerSecret} utilizado por la operación.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    public DownloadRequestOwner(
            UserAccountStore users,
            @Value("${app.download.anonymous-owner-secret}") String anonymousOwnerSecret) {
        if (anonymousOwnerSecret == null || anonymousOwnerSecret.isBlank()) {
            throw new IllegalStateException("app.download.anonymous-owner-secret must be configured");
        }
        this.users = users;
        this.secret = anonymousOwnerSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @param browserToken Valor de {@code browserToken} utilizado por la operación.
     * @param remoteAddress Valor de {@code remoteAddress} utilizado por la operación.
     * @return Resultado producido por {@code resolve}.
     */
    public RequestOwner resolve(String username, String browserToken, String remoteAddress) {
        UUID userId = username == null || username.isBlank() ? null : users
                .findByNormalizedUsername(username.strip().toLowerCase(Locale.ROOT))
                .map(account -> account.id())
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
        String browserHash = browserToken == null || browserToken.isBlank() ? null : hash(browserToken);
        String ipHash = remoteAddress == null || remoteAddress.isBlank() ? null : hash("ip:" + remoteAddress);
        return new RequestOwner(userId, browserHash, ipHash);
    }

    /**
     * Indica si existe el recurso mediante {@code hash}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code hash}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
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
     * Representa los datos inmutables de {@code RequestOwner}.
     *
     * @param userId Valor de {@code userId} incluido en el record.
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} incluido en el record.
     * @param anonymousIpHash Valor de {@code anonymousIpHash} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record RequestOwner(UUID userId, String anonymousOwnerHash, String anonymousIpHash) {
        /**
         * Ejecuta la operación {@code authenticated}.
         *
         * @return Indica si se cumple la condición evaluada.
         */
        public boolean authenticated() {
            return userId != null;
        }

        /**
         * Indica si puede realizarse la operación mediante {@code canAccess}.
         *
         * @param ownerId Identificador de {@code owner} utilizado por la operación.
         * @param jobAnonymousOwnerHash Valor de {@code jobAnonymousOwnerHash} utilizado por la
         *     operación.
         * @return Indica si se cumple la condición evaluada.
         */
        public boolean canAccess(UUID ownerId, String jobAnonymousOwnerHash) {
            return (userId != null && userId.equals(ownerId))
                    || (anonymousOwnerHash != null && anonymousOwnerHash.equals(jobAnonymousOwnerHash));
        }

        /**
         * Ejecuta la operación {@code requireAnonymousOwnerHash}.
         *
         * @return Resultado producido por {@code requireAnonymousOwnerHash}.
         * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
         *     requeridas.
         */
        public String requireAnonymousOwnerHash() {
            if (anonymousOwnerHash == null || anonymousOwnerHash.isBlank()) {
                throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
            }
            return anonymousOwnerHash;
        }
    }
}
