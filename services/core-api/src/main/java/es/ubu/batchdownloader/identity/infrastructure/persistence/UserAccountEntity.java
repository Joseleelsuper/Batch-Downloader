package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
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
 * Implementa el componente {@code UserAccountEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "core_users")
class UserAccountEntity {
    /**
     * Estado {@code id} mantenido por {@code UserAccountEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Estado {@code username} mantenido por {@code UserAccountEntity}.
     */
    @Column(nullable = false, length = 80)
    private String username;
    /**
     * Estado {@code normalizedUsername} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "normalized_username", nullable = false, length = 80, unique = true)
    private String normalizedUsername;
    /**
     * Estado {@code email} mantenido por {@code UserAccountEntity}.
     */
    @Column(nullable = false, length = 320)
    private String email;
    /**
     * Estado {@code normalizedEmail} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;
    /**
     * Estado {@code passwordHash} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    /**
     * Estado {@code emailVerified} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;
    /**
     * Estado {@code role} mantenido por {@code UserAccountEntity}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;
    /**
     * Estado {@code notifyOnJobCompletion} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "notify_on_job_completion", nullable = false)
    private boolean notifyOnJobCompletion;
    /**
     * Estado {@code enabled} mantenido por {@code UserAccountEntity}.
     */
    @Column(nullable = false)
    private boolean enabled;
    /**
     * Estado {@code createdAt} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code UserAccountEntity}.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /**
     * Estado {@code version} mantenido por {@code UserAccountEntity}.
     */
    @Version
    private long version;

    /**
     * Inicializa una instancia de {@code UserAccountEntity}.
     */
    protected UserAccountEntity() {}

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param account Valor de {@code account} utilizado por la operación.
     * @return Resultado producido por {@code from}.
     */
    static UserAccountEntity from(UserAccount account) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.id = account.id();
        entity.updateFrom(account);
        entity.version = account.version();
        return entity;
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateFrom}.
     *
     * @param account Valor de {@code account} utilizado por la operación.
     */
    void updateFrom(UserAccount account) {
        username = account.username();
        normalizedUsername = account.normalizedUsername();
        email = account.email();
        normalizedEmail = account.normalizedEmail();
        passwordHash = account.passwordHash();
        emailVerified = account.emailVerified();
        role = account.role();
        notifyOnJobCompletion = account.notifyOnJobCompletion();
        enabled = account.enabled();
        createdAt = account.createdAt();
        updatedAt = account.updatedAt();
    }

    /**
     * Convierte el valor recibido mediante {@code toDomain}.
     *
     * @return Resultado producido por {@code toDomain}.
     */
    UserAccount toDomain() {
        return UserAccount.rehydrate(
                id, username, normalizedUsername, email, normalizedEmail, passwordHash, emailVerified, role,
                notifyOnJobCompletion, enabled, createdAt, updatedAt, version);
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    UUID id() { return id; }
}
