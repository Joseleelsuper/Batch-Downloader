package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountStore {
    boolean existsByNormalizedUsername(String normalizedUsername);
    boolean existsByNormalizedEmail(String normalizedEmail);
    Optional<UserAccount> findById(UUID id);
    Optional<UserAccount> findByNormalizedUsername(String normalizedUsername);
    Optional<UserAccount> findByNormalizedEmail(String normalizedEmail);
    UserAccount save(UserAccount account);
}
