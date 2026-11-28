package es.ubu.batchdownloader.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.admin.AdminScraperNotifier;
import es.ubu.batchdownloader.admin.AdminScraperRepository;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperEvent;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.AdminSessionVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.minimum-idle=1", "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=3000", "app.auth.magic-link-ttl=15m"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IdentityService.class, JpaUserAccountStore.class, JpaIdentityTokenStore.class, JpaPendingMagicLinkStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdentityLifecycleMySqlTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    @Autowired IdentityService identities;
    @Autowired UserAccountStore users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean IdentityEventPublisher events;
    @MockitoBean Clock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void infrastructure() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("CREATE TABLE IF NOT EXISTS pending_magic_link_guard (id TINYINT NOT NULL PRIMARY KEY)");
        jdbc.update("INSERT IGNORE INTO pending_magic_link_guard VALUES (1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS SPRING_SESSION (PRIMARY_ID VARCHAR(36) PRIMARY KEY, PRINCIPAL_NAME VARCHAR(36))");
    }

    @Test
    void simultaneousIssuanceAndConsumptionForAnExistingAccount() throws Exception {
        UserAccount user = users.save(UserAccount.createUser(
                "existing", "existing", "Existing@example.test", "existing@example.test", NOW));
        assertSingleDeliveryAndConfirmation(user.normalizedEmail(), user.email(), "user");
    }

    @Test
    void simultaneousIssuanceAndConsumptionForAPendingAccount() throws Exception {
        assertSingleDeliveryAndConfirmation("pending@example.test", "pending@example.test", "pending_magic_link");
    }

    @Test
    void rotatesThePasswordAndRevokesASessionObservedByAnotherReplica() throws Exception {
        var encoder = new BCryptPasswordEncoder(4);
        var transactions = new TransactionTemplate(transactionManager);
        String email = "admin@example.test";
        var initial = new AdminBootstrap(users, clock, "admin", email, "Admin-Original-123!", encoder, transactions, jdbc);
        initial.run(null);
        var account = users.findByNormalizedUsername("admin").orElseThrow();
        var principal = AccountPrincipal.from(account);
        var previous = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var observer = new AdminSessionVersion(users);
        jdbc.update("INSERT INTO SPRING_SESSION VALUES (?, ?)", UUID.randomUUID().toString(), account.id().toString());

        var repository = mock(AdminScraperRepository.class);
        var mapper = mock(ObjectMapper.class);
        when(repository.event()).thenReturn(new ScraperEvent("scraper.changed", "1", List.of(), LocalDateTime.MIN));
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getPrincipal()).thenReturn(previous);
        var notifier = new AdminScraperNotifier(repository, mapper, observer);
        notifier.afterConnectionEstablished(socket);

        initial.run(null);
        assertThat(observer.isCurrent(previous)).isTrue();
        assertThat(sessionCount(account.id())).isEqualTo(1);

        String revoke = "DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?";
        var failingJdbc = mock(JdbcTemplate.class);
        when(failingJdbc.update(revoke, account.id().toString())).thenAnswer(invocation -> {
            jdbc.update(revoke, account.id().toString());
            throw new IllegalStateException("test rollback");
        });
        var failed = new AdminBootstrap(users, clock, "admin", email, "Admin-Rotated-456!", encoder, transactions, failingJdbc);
        assertThatThrownBy(() -> failed.run(null)).hasMessage("test rollback");
        assertThat(sessionCount(account.id())).isEqualTo(1);
        assertThat(observer.isCurrent(previous)).isTrue();

        new AdminBootstrap(users, clock, "admin", email, "Admin-Rotated-456!", encoder, transactions, jdbc).run(null);
        assertThat(sessionCount(account.id())).isZero();
        assertThat(observer.isCurrent(previous)).isFalse();
        notifier.publishIfChanged();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(socket, times(1)).sendMessage(any(TextMessage.class));

        var rotated = users.findById(account.id()).orElseThrow();
        var current = AccountPrincipal.from(rotated);
        assertThat(encoder.matches("Admin-Rotated-456!", rotated.passwordHash())).isTrue();
        assertThat(observer.isCurrent(UsernamePasswordAuthenticationToken.authenticated(
                current, null, current.getAuthorities()))).isTrue();
    }

    private int sessionCount(UUID accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Integer.class, accountId.toString());
    }

    private void assertSingleDeliveryAndConfirmation(String email, String recipient, String type) throws Exception {
        inParallel(() -> {
            identities.requestMagicLink(email, "es");
            return true;
        });
        var token = ArgumentCaptor.forClass(String.class);
        verify(events).magicLinkRequested(eq(type), any(UUID.class), eq(recipient), token.capture(), eq("es"), anyLong());

        assertThat(inParallel(() -> {
            try {
                return identities.consumeMagicLink(token.getValue()).emailVerified();
            } catch (GoneException | BadRequestException alreadyUsed) {
                return false;
            }
        })).containsExactlyInAnyOrder(true, false);
    }

    private static <T> List<T> inParallel(Callable<T> operation) throws Exception {
        var start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<T> task = () -> {
                start.await();
                return operation.call();
            };
            var first = threads.submit(task);
            var second = threads.submit(task);
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }
}
