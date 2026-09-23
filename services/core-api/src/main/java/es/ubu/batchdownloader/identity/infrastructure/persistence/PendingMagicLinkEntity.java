package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.PendingMagicLink;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Mapea solicitudes de magic link que todavía no tienen fila en {@code core_users}. */
@Entity
@Table(name = "pending_magic_link_requests")
class PendingMagicLinkEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;
    @Column(nullable = false, length = 32)
    private String locale;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected PendingMagicLinkEntity() {}

    static PendingMagicLinkEntity from(PendingMagicLink request) {
        PendingMagicLinkEntity entity = new PendingMagicLinkEntity();
        entity.id = request.id();
        entity.version = request.version();
        entity.updateFrom(request);
        return entity;
    }

    void updateFrom(PendingMagicLink request) {
        email = request.email();
        normalizedEmail = request.normalizedEmail();
        tokenHash = request.tokenHash();
        locale = request.locale();
        expiresAt = request.expiresAt();
        consumedAt = request.consumedAt();
        createdAt = request.createdAt();
    }

    PendingMagicLink toDomain() {
        return PendingMagicLink.rehydrate(
                id, email, normalizedEmail, tokenHash, locale, expiresAt, consumedAt, createdAt, version);
    }
}
