package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.AuthAttemptBudget;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Ventanas desde el primer intento, con tope de 20.000 huellas por ámbito entre todas las API. */
@Repository
public class JdbcAuthAttemptBudget implements AuthAttemptBudget {
    private static final Set<String> SCOPES = Set.of("admin-login-ip", "admin-login-account", "magic-email", "magic-ip", "magic-confirm");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcAuthAttemptBudget(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc; this.transactions = transactions; this.clock = clock;
    }

    private <T> T locked(Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT id FROM identity_auth_attempt_guard WHERE id=1 FOR UPDATE", Integer.class);
            return operation.get();
        });
    }

    @Override
    public boolean allow(String scope, String fingerprint, int limit, Duration window) {
        if (!SCOPES.contains(scope) || !fingerprint.matches("[0-9a-f]{64}") || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("invalid_auth_budget");
        // Confirmar antes del 429 del llamador: el rechazo no revierte el contador.
        return locked(() -> {
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("DELETE FROM identity_auth_attempts WHERE scope=? AND expires_at<=?", scope, now);
            var counts = jdbc.queryForList("SELECT attempt_count FROM identity_auth_attempts WHERE scope=? AND fingerprint=?",
                    Long.class, scope, fingerprint);
            if (counts.isEmpty()) {
                if (limit < 1 || jdbc.queryForObject("SELECT COUNT(*) FROM identity_auth_attempts WHERE scope=?", Long.class, scope) >= 20_000)
                    return false;
                jdbc.update("INSERT INTO identity_auth_attempts(scope,fingerprint,attempt_count,expires_at) VALUES (?,?,1,?)",
                        scope, fingerprint, Timestamp.from(clock.instant().plus(window)));
                return true;
            }
            long attempts = Math.min((long) limit + 1, counts.getFirst() + 1);
            jdbc.update("UPDATE identity_auth_attempts SET attempt_count=? WHERE scope=? AND fingerprint=?", attempts, scope, fingerprint);
            return attempts <= limit;
        });
    }

    @Scheduled(fixedDelay = 60_000)
    public void prune() {
        locked(() -> jdbc.update("DELETE FROM identity_auth_attempts WHERE expires_at<=?", Timestamp.from(clock.instant())));
    }
}
