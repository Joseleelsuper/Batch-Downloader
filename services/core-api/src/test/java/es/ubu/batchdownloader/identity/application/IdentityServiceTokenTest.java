package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.PendingMagicLinkStore;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.PendingMagicLink;
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
    private PendingMagicLinkStore pending;
    private IdentityEventPublisher events;
    private IdentityService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountStore.class);
        tokens = mock(IdentityTokenStore.class);
        pending = mock(PendingMagicLinkStore.class);
        events = mock(IdentityEventPublisher.class);
        service = new IdentityService(
                users, tokens, pending, events, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
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
        verify(events).magicLinkRequested(
                eq("user"), eq(user.id()), eq(user.email()), anyString(), eq("es"), eq(15L));
    }

    @Test
    void storesAnUnknownEmailWithoutCreatingAUser() {
        when(users.findByNormalizedEmail("new@example.com")).thenReturn(Optional.empty());
        when(pending.findByNormalizedEmailForUpdate("new@example.com")).thenReturn(Optional.empty());
        when(pending.save(any(PendingMagicLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.requestMagicLink("new@example.com", "es");

        verify(users, never()).save(any(UserAccount.class));
        var request = org.mockito.ArgumentCaptor.forClass(PendingMagicLink.class);
        verify(pending).save(request.capture());
        assertThat(request.getValue().email()).isEqualTo("new@example.com");
        assertThat(request.getValue().locale()).isEqualTo("es");
        verify(events).magicLinkRequested(
                eq("pending_magic_link"), eq(request.getValue().id()), eq("new@example.com"),
                anyString(), eq("es"), eq(15L));
    }

    @Test
    void confirmsPendingLinkAndCreatesExactlyOneVerifiedAccount() {
        String rawToken = "pending-token";
        PendingMagicLink request = PendingMagicLink.issue(
                "new@example.com", "new@example.com", IdentityService.hashToken(rawToken),
                "es", NOW.plusSeconds(60), NOW);
        when(pending.findByHashForUpdate(IdentityService.hashToken(rawToken)))
                .thenReturn(Optional.of(request));
        when(users.existsByNormalizedUsername("new")).thenReturn(false);
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pending.save(any(PendingMagicLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount first = service.consumeMagicLink(rawToken);

        assertThat(first.email()).isEqualTo("new@example.com");
        assertThat(first.emailVerified()).isTrue();
        verify(users, org.mockito.Mockito.times(2)).save(first);
        verify(pending).save(request);
        assertThat(request.consumedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> service.consumeMagicLink(rawToken))
                .isInstanceOfSatisfying(GoneException.class,
                        exception -> assertThat(exception.code()).isEqualTo("magic_link_used"));
    }

    @Test
    void renewsPendingRequestAndInvalidatesItsPreviousHash() {
        PendingMagicLink request = PendingMagicLink.issue(
                "new@example.com", "new@example.com", "old-hash", "es",
                NOW.plusSeconds(60), NOW);
        when(users.findByNormalizedEmail("new@example.com")).thenReturn(Optional.empty());
        when(pending.findByNormalizedEmailForUpdate("new@example.com"))
                .thenReturn(Optional.of(request));
        when(pending.save(any(PendingMagicLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.requestMagicLink("new@example.com", "es");

        assertThat(request.tokenHash()).isNotEqualTo("old-hash");
        assertThat(request.consumedAt()).isNull();
        verify(pending).save(request);
    }
}
