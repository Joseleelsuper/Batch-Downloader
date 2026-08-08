package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Implementa el componente {@code IdentityTokenEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "identity_tokens")
class IdentityTokenEntity {
    /**
     * Estado {@code id} mantenido por {@code IdentityTokenEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Estado {@code userId} mantenido por {@code IdentityTokenEntity}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;
    /**
     * Estado {@code tokenHash} mantenido por {@code IdentityTokenEntity}.
     */
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;
    /**
     * Estado {@code type} mantenido por {@code IdentityTokenEntity}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", length = 32, nullable = false)
    private IdentityToken.Type type;
    /**
     * Estado {@code expiresAt} mantenido por {@code IdentityTokenEntity}.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    /**
     * Estado {@code consumedAt} mantenido por {@code IdentityTokenEntity}.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;
    /**
     * Estado {@code createdAt} mantenido por {@code IdentityTokenEntity}.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    /**
     * Inicializa una instancia de {@code IdentityTokenEntity}.
     */
    protected IdentityTokenEntity() {}

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param token Token utilizado para autorizar o correlacionar la operación.
     * @return Resultado producido por {@code from}.
     */
    static IdentityTokenEntity from(IdentityToken token) {
        IdentityTokenEntity entity = new IdentityTokenEntity();
        entity.id = token.id();
        entity.version = token.version();
        entity.updateFrom(token);
        return entity;
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateFrom}.
     *
     * @param token Token utilizado para autorizar o correlacionar la operación.
     */
    void updateFrom(IdentityToken token) {
        userId = token.userId();
        tokenHash = token.tokenHash();
        type = token.type();
        expiresAt = token.expiresAt();
        consumedAt = token.consumedAt();
        createdAt = token.createdAt();
    }

    /**
     * Convierte el valor recibido mediante {@code toDomain}.
     *
     * @return Resultado producido por {@code toDomain}.
     */
    IdentityToken toDomain() {
        return IdentityToken.rehydrate(id, userId, tokenHash, type, expiresAt, consumedAt, createdAt, version);
    }
}
