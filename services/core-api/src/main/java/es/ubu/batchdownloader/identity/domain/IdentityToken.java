package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Representa un enlace de acceso de un solo uso, almacenado únicamente como hash. */
public final class IdentityToken {
    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant consumedAt;
    private long version;

    private IdentityToken(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    public static IdentityToken issue(
            UUID userId, String tokenHash, Instant expiresAt, Instant now) {
        return new IdentityToken(UUID.randomUUID(), userId, tokenHash, expiresAt, null, now, 0);
    }

    public static IdentityToken rehydrate(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt,
            long version) {
        return new IdentityToken(id, userId, tokenHash, expiresAt, consumedAt, createdAt, version);
    }

    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        if (!usableAt(now)) throw new IllegalStateException("token_not_usable");
        consumedAt = now;
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String tokenHash() { return tokenHash; }
    public Instant expiresAt() { return expiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
