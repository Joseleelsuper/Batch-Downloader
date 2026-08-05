package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.common.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifica que BCrypt no retenga una conexión de MySQL. */
class IdentityServiceTransactionTest {
    @Test
    void hashesRegistrationPasswordBeforeOpeningTheWriteTransaction() {
        AtomicBoolean transactionActive = new AtomicBoolean();
        UserAccountStore users = mock(UserAccountStore.class);
        IdentityTokenStore tokens = mock(IdentityTokenStore.class);
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash(anyString())).thenAnswer(invocation -> {
            assertThat(transactionActive).isFalse();
            return "bcrypt-hash";
        });
        when(users.save(any())).thenAnswer(invocation -> {
            assertThat(transactionActive).isTrue();
            return invocation.getArgument(0);
        });
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IdentityService service = new IdentityService(
                users,
                tokens,
                passwords,
                mock(IdentityEventPublisher.class),
                Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(24),
                Duration.ofHours(1),
                new TransactionTemplate(new FlagTransactionManager(transactionActive)));

        service.register("user", "user@example.com", "password");

        assertThat(transactionActive).isFalse();
    }

    @Test
    void translatesAnAtomicUsernameCollisionAfterThePrecheck() {
        AtomicBoolean transactionActive = new AtomicBoolean();
        UserAccountStore users = mock(UserAccountStore.class);
        IdentityTokenStore tokens = mock(IdentityTokenStore.class);
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash(anyString())).thenReturn("bcrypt-hash");
        when(users.existsByNormalizedUsername("user")).thenReturn(false, false, true);
        when(users.existsByNormalizedEmail("user@example.com")).thenReturn(false, false);
        when(users.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        IdentityService service = new IdentityService(
                users,
                tokens,
                passwords,
                mock(IdentityEventPublisher.class),
                Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofHours(24),
                Duration.ofHours(1),
                new TransactionTemplate(new FlagTransactionManager(transactionActive)));

        assertThatThrownBy(() -> service.register(
                        "user", "user@example.com", "password"))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("username_already_exists"));
        assertThat(transactionActive).isFalse();
    }

    /** Gestor mínimo que hace observable el límite exacto de la transacción. */
    private static final class FlagTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicBoolean active;

        private FlagTransactionManager(AtomicBoolean active) {
            this.active = active;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active.set(false);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active.set(false);
        }
    }
}
