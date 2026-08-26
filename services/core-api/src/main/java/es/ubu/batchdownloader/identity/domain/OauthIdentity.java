package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identidad estable emitida por un proveedor OpenID Connect. */
public final class OauthIdentity {
    public enum Provider { GOOGLE }

    private final UUID id;
    private final UUID userId;
    private final Provider provider;
    private final String subject;
    private String providerEmail;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;
    private long version;

    private OauthIdentity(
            UUID id,
            UUID userId,
            Provider provider,
            String subject,
            String providerEmail,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.provider = Objects.requireNonNull(provider);
        this.subject = requireText(subject, "subject");
        this.providerEmail = requireText(providerEmail, "providerEmail");
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.lastLoginAt = Objects.requireNonNull(lastLoginAt);
        this.version = version;
    }

    public static OauthIdentity link(
            UUID userId, Provider provider, String subject, String providerEmail, Instant now) {
        return new OauthIdentity(
                UUID.randomUUID(), userId, provider, subject, providerEmail, now, now, now, 0);
    }

    public static OauthIdentity rehydrate(
            UUID id,
            UUID userId,
            Provider provider,
            String subject,
            String providerEmail,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt,
            long version) {
        return new OauthIdentity(
                id, userId, provider, subject, providerEmail, createdAt, updatedAt, lastLoginAt, version);
    }

    public void recordLogin(String email, Instant now) {
        providerEmail = requireText(email, "providerEmail");
        updatedAt = Objects.requireNonNull(now);
        lastLoginAt = now;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public Provider provider() { return provider; }
    public String subject() { return subject; }
    public String providerEmail() { return providerEmail; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant lastLoginAt() { return lastLoginAt; }
    public long version() { return version; }
}
