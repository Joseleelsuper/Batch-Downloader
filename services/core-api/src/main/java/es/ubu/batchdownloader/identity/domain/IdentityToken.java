package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IdentityToken {
    public enum Type { EMAIL_VERIFICATION, PASSWORD_RESET }

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Type type;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant consumedAt;

    private IdentityToken(
            UUID id, UUID userId, String tokenHash, Type type, Instant expiresAt, Instant consumedAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.type = Objects.requireNonNull(type);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static IdentityToken issue(UUID userId, String tokenHash, Type type, Instant expiresAt, Instant now) {
        return new IdentityToken(UUID.randomUUID(), userId, tokenHash, type, expiresAt, null, now);
    }

    public static IdentityToken rehydrate(
            UUID id, UUID userId, String tokenHash, Type type, Instant expiresAt, Instant consumedAt, Instant createdAt) {
        return new IdentityToken(id, userId, tokenHash, type, expiresAt, consumedAt, createdAt);
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
    public Type type() { return type; }
    public Instant expiresAt() { return expiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public Instant createdAt() { return createdAt; }
}
