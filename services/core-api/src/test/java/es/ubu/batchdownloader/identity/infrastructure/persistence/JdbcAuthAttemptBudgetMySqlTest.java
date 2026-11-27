package es.ubu.batchdownloader.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class JdbcAuthAttemptBudgetMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.13");
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private final MutableClock clock = new MutableClock();

    @BeforeAll static void schema() {
        source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V28__shared_auth_attempt_budgets.sql")).execute(source);
    }

    @BeforeEach void reset() { jdbc.update("DELETE FROM identity_auth_attempts"); }

    private JdbcAuthAttemptBudget replica() {
        return new JdbcAuthAttemptBudget(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), clock);
    }

    @Test void concurrentReplicasShareTheLimitAndRejectedAttemptsRemainCounted() throws Exception {
        var first = replica(); var second = replica(); var start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(index -> threads.submit(() -> {
                start.await();
                return (index % 2 == 0 ? first : second).allow("admin-login-account", "a".repeat(64), 5, Duration.ofMinutes(1));
            })).toList();
            start.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM identity_auth_attempts", Long.class)).isEqualTo(6);
        assertThat(replica().allow("admin-login-account", "a".repeat(64), 5, Duration.ofMinutes(1))).isFalse();
    }

    @Test void retriesDoNotExtendTheWindowAndExpiredFingerprintsAreRemoved() {
        var first = replica();
        assertThat(first.allow("magic-email", "b".repeat(64), 1, Duration.ofHours(1))).isTrue();
        clock.advance(3599);
        assertThat(replica().allow("magic-email", "b".repeat(64), 1, Duration.ofHours(1))).isFalse();
        clock.advance(1);
        replica().prune();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_auth_attempts", Integer.class)).isZero();
        assertThat(replica().allow("magic-email", "b".repeat(64), 1, Duration.ofHours(1))).isTrue();
    }

    @Test void fullScopeRejectsNewKeysWithoutEvictingLiveCountersAndRecoversAfterExpiry() {
        String digits = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4"
                + " UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.update("INSERT INTO identity_auth_attempts(scope,fingerprint,attempt_count,expires_at) "
                + "SELECT 'admin-login-ip',LPAD(a.n+10*b.n+100*c.n+1000*d.n+10000*e.n,64,'0'),1,? FROM "
                + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " c CROSS JOIN " + digits
                + " d CROSS JOIN (SELECT 0 n UNION ALL SELECT 1) e", Timestamp.from(clock.instant().plusSeconds(60)));
        assertThat(replica().allow("admin-login-ip", "f".repeat(64), 5, Duration.ofMinutes(1))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_auth_attempts", Integer.class)).isEqualTo(20_000);
        clock.advance(60);
        assertThat(replica().allow("admin-login-ip", "f".repeat(64), 5, Duration.ofMinutes(1))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_auth_attempts", Integer.class)).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T12:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
