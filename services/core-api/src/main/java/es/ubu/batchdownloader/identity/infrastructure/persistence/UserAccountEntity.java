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

@Entity
@Table(name = "core_users")
class UserAccountEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @Column(nullable = false, length = 80)
    private String username;
    @Column(name = "normalized_username", nullable = false, length = 80, unique = true)
    private String normalizedUsername;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;
    @Column(name = "notify_on_job_completion", nullable = false)
    private boolean notifyOnJobCompletion;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected UserAccountEntity() {}

    static UserAccountEntity from(UserAccount account) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.id = account.id();
        entity.updateFrom(account);
        entity.version = account.version();
        return entity;
    }

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

    UserAccount toDomain() {
        return UserAccount.rehydrate(
                id, username, normalizedUsername, email, normalizedEmail, passwordHash, emailVerified, role,
                notifyOnJobCompletion, enabled, createdAt, updatedAt, version);
    }

    UUID id() { return id; }
}
