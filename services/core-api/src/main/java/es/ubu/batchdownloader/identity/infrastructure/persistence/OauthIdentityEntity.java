package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.OauthIdentity;
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
@Table(name = "oauth_identities")
class OauthIdentityEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OauthIdentity.Provider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "provider_email", nullable = false, length = 320)
    private String providerEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    @Version
    private long version;

    protected OauthIdentityEntity() {}

    static OauthIdentityEntity from(OauthIdentity identity) {
        OauthIdentityEntity entity = new OauthIdentityEntity();
        entity.id = identity.id();
        entity.version = identity.version();
        entity.updateFrom(identity);
        return entity;
    }

    void updateFrom(OauthIdentity identity) {
        userId = identity.userId();
        provider = identity.provider();
        subject = identity.subject();
        providerEmail = identity.providerEmail();
        createdAt = identity.createdAt();
        updatedAt = identity.updatedAt();
        lastLoginAt = identity.lastLoginAt();
    }

    OauthIdentity toDomain() {
        return OauthIdentity.rehydrate(
                id, userId, provider, subject, providerEmail, createdAt, updatedAt, lastLoginAt, version);
    }
}
