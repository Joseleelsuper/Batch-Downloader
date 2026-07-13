package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "identity_tokens")
class IdentityTokenEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", length = 32, nullable = false)
    private IdentityToken.Type type;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdentityTokenEntity() {}

    static IdentityTokenEntity from(IdentityToken token) {
        IdentityTokenEntity entity = new IdentityTokenEntity();
        entity.id = token.id();
        entity.updateFrom(token);
        return entity;
    }

    void updateFrom(IdentityToken token) {
        userId = token.userId();
        tokenHash = token.tokenHash();
        type = token.type();
        expiresAt = token.expiresAt();
        consumedAt = token.consumedAt();
        createdAt = token.createdAt();
    }

    IdentityToken toDomain() {
        return IdentityToken.rehydrate(id, userId, tokenHash, type, expiresAt, consumedAt, createdAt);
    }
}
