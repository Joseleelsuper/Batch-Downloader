package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.GoogleOauthAccountService.OauthLoginException;
import es.ubu.batchdownloader.identity.application.port.OauthIdentityStore;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.OauthIdentity;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Clock;
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

class GoogleOauthAccountServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");
    private UserAccountStore users;
    private OauthIdentityStore identities;
    private IdentityService identityService;
    private GoogleOauthAccountService service;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserAccountStore.class);
        identities = Mockito.mock(OauthIdentityStore.class);
        identityService = Mockito.mock(IdentityService.class);
        service = new GoogleOauthAccountService(
                users, identities, identityService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new TransactionTemplate(new NoopTransactionManager()));
    }

    @Test
    void updatesOnlyTheObservedProviderEmailForAnExistingSubject() {
        UserAccount user = user(UserRole.USER, true, null);
        OauthIdentity linked = OauthIdentity.link(
                user.id(), OauthIdentity.Provider.GOOGLE, "google-sub", "old@example.com", NOW.minusSeconds(30));
        when(identities.findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, "google-sub"))
                .thenReturn(Optional.of(linked));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(identities.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount result = service.resolve("google-sub", "new@example.com", true);

        assertThat(result.email()).isEqualTo("person@example.com");
        ArgumentCaptor<OauthIdentity> saved = ArgumentCaptor.forClass(OauthIdentity.class);
        verify(identities).save(saved.capture());
        assertThat(saved.getValue().providerEmail()).isEqualTo("new@example.com");
        assertThat(saved.getValue().lastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void linksAndVerifiesAnExistingLocalUser() {
        UserAccount local = user(UserRole.USER, false, "hash");
        UserAccount verified = user(local.id(), UserRole.USER, true, "hash");
        when(identities.findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, "google-sub"))
                .thenReturn(Optional.empty());
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(local));
        when(users.findById(local.id())).thenReturn(Optional.of(local));
        when(identities.existsByUserIdAndProvider(local.id(), OauthIdentity.Provider.GOOGLE))
                .thenReturn(false);
        when(identityService.markVerified(local.id())).thenReturn(verified);
        when(identities.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount result = service.resolve("google-sub", "Person@Example.com", true);

        assertThat(result.emailVerified()).isTrue();
        verify(identityService).markVerified(local.id());
        ArgumentCaptor<OauthIdentity> saved = ArgumentCaptor.forClass(OauthIdentity.class);
        verify(identities).save(saved.capture());
        assertThat(saved.getValue().subject()).isEqualTo("google-sub");
        assertThat(saved.getValue().userId()).isEqualTo(local.id());
    }

    @Test
    void createsAnOauthOnlyUserWhenTheEmailDoesNotExist() {
        UserAccount created = user(UserRole.USER, true, null);
        when(identities.findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, "new-sub"))
                .thenReturn(Optional.empty());
        when(users.findByNormalizedEmail("new@example.com")).thenReturn(Optional.empty());
        when(identityService.createOauthAccount("new@example.com")).thenReturn(created);
        when(users.findById(created.id())).thenReturn(Optional.of(created));
        when(identities.existsByUserIdAndProvider(created.id(), OauthIdentity.Provider.GOOGLE))
                .thenReturn(false);
        when(identities.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.resolve("new-sub", "new@example.com", true)).isSameAs(created);
        verify(identityService).createOauthAccount("new@example.com");
        verify(identityService, never()).markVerified(any());
    }

    @Test
    void rejectsUnverifiedClaimsAndNeverAutoLinksAnAdmin() {
        assertThatThrownBy(() -> service.resolve("sub", "person@example.com", false))
                .isInstanceOfSatisfying(OauthLoginException.class,
                        exception -> assertThat(exception.publicCode())
                                .isEqualTo("oauth_email_not_verified"));

        UserAccount admin = user(UserRole.ADMIN, true, "hash");
        when(identities.findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, "admin-sub"))
                .thenReturn(Optional.empty());
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(admin));
        when(users.findById(admin.id())).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.resolve("admin-sub", "person@example.com", true))
                .isInstanceOfSatisfying(OauthLoginException.class,
                        exception -> assertThat(exception.publicCode())
                                .isEqualTo("oauth_admin_link_forbidden"));
        verify(identities, never()).save(any());
    }

    private static UserAccount user(UserRole role, boolean verified, String hash) {
        return user(UUID.randomUUID(), role, verified, hash);
    }

    private static UserAccount user(UUID id, UserRole role, boolean verified, String hash) {
        return UserAccount.rehydrate(
                id, role == UserRole.ADMIN ? "admin" : "person",
                role == UserRole.ADMIN ? "admin" : "person",
                "person@example.com", "person@example.com", hash, verified, role,
                true, true, NOW.minusSeconds(3600), NOW.minusSeconds(3600), 0);
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
