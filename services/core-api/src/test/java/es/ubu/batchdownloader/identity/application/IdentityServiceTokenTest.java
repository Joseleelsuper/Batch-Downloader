package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class IdentityServiceTokenTest {
    private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");
    private UserAccountStore users;
    private IdentityTokenStore tokens;
    private PasswordHasher passwords;
    private IdentityEventPublisher events;
    private AccountSessionInvalidator sessions;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserAccountStore.class);
        tokens = Mockito.mock(IdentityTokenStore.class);
        passwords = Mockito.mock(PasswordHasher.class);
        events = Mockito.mock(IdentityEventPublisher.class);
        sessions = Mockito.mock(AccountSessionInvalidator.class);
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new IdentityService(
                users, tokens, passwords, events, sessions,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), Duration.ofHours(1),
                new TransactionTemplate(new NoopTransactionManager()));
    }

    @Test
    void atomicallyConsumesAValidVerificationToken() {
        UserAccount user = user(false, "hash");
        IdentityToken token = token(user.id(), IdentityToken.Type.EMAIL_VERIFICATION,
                NOW.plusSeconds(60), null);
        when(tokens.findByHashAndTypeForUpdate(
                IdentityService.hashToken("verification-token"),
                IdentityToken.Type.EMAIL_VERIFICATION)).thenReturn(Optional.of(token));
        when(users.findById(user.id())).thenReturn(Optional.of(user));

        service.confirmEmail("verification-token");

        assertThat(user.emailVerified()).isTrue();
        assertThat(token.consumedAt()).isEqualTo(NOW);
        verify(tokens).findByHashAndTypeForUpdate(
                IdentityService.hashToken("verification-token"),
                IdentityToken.Type.EMAIL_VERIFICATION);
        verify(tokens).save(token);
    }

    @Test
    void returnsStableInvalidExpiredAndUsedVerificationCodes() {
        String hash = IdentityService.hashToken("token");
        when(tokens.findByHashAndTypeForUpdate(hash, IdentityToken.Type.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(token(
                        UUID.randomUUID(), IdentityToken.Type.EMAIL_VERIFICATION, NOW, null)))
                .thenReturn(Optional.of(token(
                        UUID.randomUUID(), IdentityToken.Type.EMAIL_VERIFICATION,
                        NOW.plusSeconds(60), NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.confirmEmail("token"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("verification_token_invalid"));
        assertThatThrownBy(() -> service.confirmEmail("token"))
                .isInstanceOfSatisfying(GoneException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("verification_token_expired"));
        assertThatThrownBy(() -> service.confirmEmail("token"))
                .isInstanceOfSatisfying(GoneException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("verification_token_used"));
    }

    @Test
    void hashesAResetPasswordBeforeTheLockedWriteAndInvalidatesEverySession() {
        UserAccount user = user(true, "old-hash");
        IdentityToken token = token(
                user.id(), IdentityToken.Type.PASSWORD_RESET, NOW.plusSeconds(60), null);
        String hash = IdentityService.hashToken("reset-token");
        when(tokens.findByHashAndType(hash, IdentityToken.Type.PASSWORD_RESET))
                .thenReturn(Optional.of(token));
        when(tokens.findByHashAndTypeForUpdate(hash, IdentityToken.Type.PASSWORD_RESET))
                .thenReturn(Optional.of(token));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(passwords.hash("New-secure1!")).thenReturn("new-hash");

        service.resetPassword("reset-token", "New-secure1!");

        assertThat(user.passwordHash()).isEqualTo("new-hash");
        assertThat(token.consumedAt()).isEqualTo(NOW);
        verify(sessions).invalidateAll(user.id());
    }

    @Test
    void reissuesVerificationForEligibleAccounts() {
        UserAccount eligible = user(false, "hash");
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(eligible));

        service.resendEmailVerification(" PERSON@example.com ");

        verify(tokens).invalidateUnconsumedForUser(
                eligible.id(), IdentityToken.Type.EMAIL_VERIFICATION, NOW);
        ArgumentCaptor<String> deliveryToken = ArgumentCaptor.forClass(String.class);
        verify(events).emailVerificationRequested(
                org.mockito.ArgumentMatchers.eq(eligible), deliveryToken.capture());
        assertThat(deliveryToken.getValue()).matches("[A-Za-z0-9_-]{43}");

    }

    private static IdentityToken token(
            UUID userId, IdentityToken.Type type, Instant expiresAt, Instant consumedAt) {
        return IdentityToken.rehydrate(
                UUID.randomUUID(), userId, IdentityService.hashToken("token"), type,
                expiresAt, consumedAt, NOW.minusSeconds(60), 0);
    }

    private static UserAccount user(boolean verified, String hash) {
        return UserAccount.rehydrate(
                UUID.randomUUID(), "person", "person", "person@example.com", "person@example.com",
                hash, verified, UserRole.USER, true, true,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600), 0);
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
