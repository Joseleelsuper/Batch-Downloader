package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Solicitud de acceso de un correo aún no asociado a una cuenta. */
public final class PendingMagicLink {
    private final UUID id;
    private final String email;
    private final String normalizedEmail;
    private String tokenHash;
    private String locale;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant createdAt;
    private long version;

    private PendingMagicLink(
            UUID id,
            String email,
            String normalizedEmail,
            String tokenHash,
            String locale,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.email = requireText(email, "email");
        this.normalizedEmail = requireText(normalizedEmail, "normalizedEmail");
        this.tokenHash = requireText(tokenHash, "tokenHash");
        this.locale = requireText(locale, "locale");
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    public static PendingMagicLink issue(
            String email,
            String normalizedEmail,
            String tokenHash,
            String locale,
            Instant expiresAt,
            Instant now) {
        return new PendingMagicLink(
                UUID.randomUUID(), email, normalizedEmail, tokenHash, locale, expiresAt, null, now, 0);
    }

    public static PendingMagicLink rehydrate(
            UUID id,
            String email,
            String normalizedEmail,
            String tokenHash,
            String locale,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt,
            long version) {
        return new PendingMagicLink(
                id, email, normalizedEmail, tokenHash, locale, expiresAt, consumedAt, createdAt, version);
    }

    /** Sustituye el enlace anterior del mismo correo sin crear una segunda fila. */
    public void renew(String tokenHash, String locale, Instant expiresAt, Instant now) {
        this.tokenHash = requireText(tokenHash, "tokenHash");
        this.locale = requireText(locale, "locale");
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = null;
        this.createdAt = Objects.requireNonNull(now);
    }

    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        if (!usableAt(now)) throw new IllegalStateException("pending_magic_link_not_usable");
        consumedAt = Objects.requireNonNull(now);
    }

    public UUID id() { return id; }
    public String email() { return email; }
    public String normalizedEmail() { return normalizedEmail; }
    public String tokenHash() { return tokenHash; }
    public String locale() { return locale; }
    public Instant expiresAt() { return expiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
