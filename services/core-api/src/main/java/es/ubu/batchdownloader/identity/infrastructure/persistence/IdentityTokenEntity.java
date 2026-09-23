package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapea hash, consumo y vencimiento de un enlace a JPA con versión para concurrencia optimista.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.IdentityToken
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.JpaIdentityTokenStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Entity
@Table(name = "identity_tokens")
class IdentityTokenEntity {
    /**
     * UUID estable del agregado que se consulta o reconstruye.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;
    /**
     * SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin almacenar su original.
     */
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;
    /**
     * Instante a partir del cual el token deja de ser utilizable, incluido el propio límite.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    /**
     * Instante de consumo o invalidación; null significa que aún no se ha consumido.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;
    /**
     * Instante de creación original del agregado.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     */
    @Version
    private long version;

    /**
     * Permite a JPA reconstruir el registro del token sin emitir otro permiso.
     */
    protected IdentityTokenEntity() {}

    /**
     * Crea la entidad de un token conservando UUID, versión y estado.
     *
     * @param token Agregado de token que conserva hash, vencimiento y consumo.
     * @return entidad nueva con los datos del agregado.
     */
    static IdentityTokenEntity from(IdentityToken token) {
        IdentityTokenEntity entity = new IdentityTokenEntity();
        entity.id = token.id();
        entity.version = token.version();
        entity.updateFrom(token);
        return entity;
    }

    /**
     * Copia hash y fechas del token sin sustituir la identidad ni la versión que
     * gestiona JPA.
     *
     * @param token Agregado de token que conserva hash, vencimiento y consumo.
     */
    void updateFrom(IdentityToken token) {
        userId = token.userId();
        tokenHash = token.tokenHash();
        expiresAt = token.expiresAt();
        consumedAt = token.consumedAt();
        createdAt = token.createdAt();
    }

    /**
     * Rehidrata el token conservando consumo, vencimiento y versión guardados.
     *
     * @return agregado equivalente a la fila persistida.
     */
    IdentityToken toDomain() {
        return IdentityToken.rehydrate(id, userId, tokenHash, expiresAt, consumedAt, createdAt, version);
    }
}
