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
 * Resolves the two ownership mechanisms accepted by download jobs without ever
 * persisting or publishing the raw browser token.
 */
@Component
public class DownloadRequestOwner {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final UserAccountStore users;
    private final byte[] secret;

    public DownloadRequestOwner(
            UserAccountStore users,
            @Value("${app.download.anonymous-owner-secret}") String anonymousOwnerSecret) {
        if (anonymousOwnerSecret == null || anonymousOwnerSecret.isBlank()) {
            throw new IllegalStateException("app.download.anonymous-owner-secret must be configured");
        }
        this.users = users;
        this.secret = anonymousOwnerSecret.getBytes(StandardCharsets.UTF_8);
    }

    public RequestOwner resolve(String username, String browserToken, String remoteAddress) {
        UUID userId = username == null || username.isBlank() ? null : users
                .findByNormalizedUsername(username.strip().toLowerCase(Locale.ROOT))
                .map(account -> account.id())
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
        String browserHash = browserToken == null || browserToken.isBlank() ? null : hash(browserToken);
        String ipHash = remoteAddress == null || remoteAddress.isBlank() ? null : hash("ip:" + remoteAddress);
        return new RequestOwner(userId, browserHash, ipHash);
    }

    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("anonymous_owner_hash_failed", exception);
        }
    }

    public record RequestOwner(UUID userId, String anonymousOwnerHash, String anonymousIpHash) {
        public boolean authenticated() {
            return userId != null;
        }

        public boolean canAccess(UUID ownerId, String jobAnonymousOwnerHash) {
            return (userId != null && userId.equals(ownerId))
                    || (anonymousOwnerHash != null && anonymousOwnerHash.equals(jobAnonymousOwnerHash));
        }

        public String requireAnonymousOwnerHash() {
            if (anonymousOwnerHash == null || anonymousOwnerHash.isBlank()) {
                throw new NotFoundException("download_job_not_found", "No existe el trabajo.");
            }
            return anonymousOwnerHash;
        }
    }
}
