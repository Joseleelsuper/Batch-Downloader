package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityServiceTokenTest {
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private UserAccountStore users;
    private IdentityTokenStore tokens;
    private IdentityEventPublisher events;
    private IdentityService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountStore.class);
        tokens = mock(IdentityTokenStore.class);
        events = mock(IdentityEventPublisher.class);
        service = new IdentityService(
                users, tokens, events, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
        user = UserAccount.createUser(
                "person", "person", "person@example.com", "person@example.com", NOW);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokens.save(any(IdentityToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void consumesTheLatestLinkAndVerifiesTheEmail() {
        String rawToken = "magic-token";
        IdentityToken token = IdentityToken.issue(
                user.id(), IdentityService.hashToken(rawToken), NOW.plusSeconds(60), NOW);
        when(tokens.findByHashForUpdate(IdentityService.hashToken(rawToken)))
                .thenReturn(Optional.of(token));

        assertThat(service.consumeMagicLink(rawToken)).isSameAs(user);
        assertThat(user.emailVerified()).isTrue();
        assertThat(token.consumedAt()).isEqualTo(NOW);
        verify(tokens).save(token);
    }

    @Test
    void rejectsExpiredAndAlreadyUsedLinks() {
        String rawToken = "expired-token";
        IdentityToken expired = IdentityToken.issue(
                user.id(), IdentityService.hashToken(rawToken), NOW, NOW.minusSeconds(1));
        when(tokens.findByHashForUpdate(IdentityService.hashToken(rawToken)))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.consumeMagicLink(rawToken))
                .isInstanceOfSatisfying(GoneException.class,
                        exception -> assertThat(exception.code()).isEqualTo("magic_link_expired"));

        String usedRaw = "used-token";
        IdentityToken used = IdentityToken.issue(
                user.id(), IdentityService.hashToken(usedRaw), NOW.plusSeconds(60), NOW);
        used.consume(NOW);
        when(tokens.findByHashForUpdate(IdentityService.hashToken(usedRaw)))
                .thenReturn(Optional.of(used));
        assertThatThrownBy(() -> service.consumeMagicLink(usedRaw))
                .isInstanceOfSatisfying(GoneException.class,
                        exception -> assertThat(exception.code()).isEqualTo("magic_link_used"));
    }

    @Test
    void invalidatesPreviousLinksAndPublishesAnEncryptedDeliveryRequest() {
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(user));

        service.requestMagicLink(" PERSON@example.com ");

        verify(tokens).invalidateUnconsumedForUser(user.id(), NOW);
        verify(events).magicLinkRequested(any(UserAccount.class), any(String.class));
    }

    @Test
    void createsAUserWithoutPasswordForAnUnknownEmail() {
        when(users.findByNormalizedEmail("new@example.com")).thenReturn(Optional.empty());
        when(users.existsByNormalizedUsername("new")).thenReturn(false);
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.requestMagicLink("new@example.com");

        var account = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(account.capture());
        assertThat(account.getValue().passwordHash()).isNull();
        assertThat(account.getValue().email()).isEqualTo("new@example.com");
        verify(events).magicLinkRequested(any(UserAccount.class), any(String.class));
    }
}
