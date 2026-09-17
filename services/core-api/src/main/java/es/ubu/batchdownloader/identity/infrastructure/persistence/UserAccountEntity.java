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
 * Persiste las credenciales, identidad estable, rol y preferencias de la cuenta con control
 * optimista de actualizaciones.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.JpaUserAccountStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Entity
@Table(name = "core_users")
class UserAccountEntity {
    /**
     * UUID estable del propietario.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Nombre visible de la cuenta, distinto de su UUID de identidad.
     */
    @Column(nullable = false, length = 80)
    private String username;
    /**
     * Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     */
    @Column(name = "normalized_username", nullable = false, length = 80, unique = true)
    private String normalizedUsername;
    /**
     * Correo de la cuenta; se conserva recortado y se compara mediante su versión normalizada.
     */
    @Column(nullable = false, length = 320)
    private String email;
    /**
     * Correo recortado y en minúsculas para consulta y unicidad.
     */
    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;
    /**
     * Hash de contraseña almacenado, nunca la contraseña original.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    /**
     * Indica que se ha confirmado el control del correo de la cuenta.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;
    /**
     * Rol USER o ADMIN que determina el acceso permitido.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;
    /**
     * Preferencia vigente de recibir correo cuando termina una descarga.
     */
    @Column(name = "notify_on_job_completion", nullable = false)
    private boolean notifyOnJobCompletion;
    /**
     * Habilita el acceso de la cuenta o la preferencia de correo según el método.
     */
    @Column(nullable = false)
    private boolean enabled;
    /**
     * Instante de creación original del agregado.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /**
     * Instante del último cambio de la cuenta.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     */
    @Version
    private long version;

    /**
     * Permite a JPA reconstruir una cuenta desde las columnas persistidas.
     */
    protected UserAccountEntity() {}

    /**
     * Crea la entidad de cuenta conservando UUID, versión y todos sus datos persistentes.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
     * @return entidad nueva que representa el agregado.
     */
    static UserAccountEntity from(UserAccount account) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.id = account.id();
        entity.updateFrom(account);
        entity.version = account.version();
        return entity;
    }

    /**
     * Copia perfil, credenciales, flags y fechas sin modificar UUID ni versión gestionada por JPA.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
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
     * Rehidrata la cuenta con su identidad, estado y versión originales.
     *
     * @return agregado independiente que representa la entidad.
     */
    UserAccount toDomain() {
        return UserAccount.rehydrate(
                id, username, normalizedUsername, email, normalizedEmail, passwordHash, emailVerified, role,
                notifyOnJobCompletion, enabled, createdAt, updatedAt, version);
    }

    /**
     * Identifica la entidad mediante el UUID canónico de la cuenta.
     *
     * @return UUID estable del propietario.
     */
    UUID id() { return id; }
}
