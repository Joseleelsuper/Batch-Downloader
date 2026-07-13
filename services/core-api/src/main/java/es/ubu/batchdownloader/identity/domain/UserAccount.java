package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class UserAccount {
    private final UUID id;
    private final String username;
    private final String normalizedUsername;
    private final String email;
    private final String normalizedEmail;
    private String passwordHash;
    private boolean emailVerified;
    private final UserRole role;
    private boolean notifyOnJobCompletion;
    private boolean enabled;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private UserAccount(
            UUID id,
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String passwordHash,
            boolean emailVerified,
            UserRole role,
            boolean notifyOnJobCompletion,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.username = requireText(username, "username");
        this.normalizedUsername = requireText(normalizedUsername, "normalizedUsername");
        this.email = requireText(email, "email");
        this.normalizedEmail = requireText(normalizedEmail, "normalizedEmail");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.emailVerified = emailVerified;
        this.role = Objects.requireNonNull(role);
        this.notifyOnJobCompletion = notifyOnJobCompletion;
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    public static UserAccount register(
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String passwordHash,
            Instant now) {
        return new UserAccount(
                UUID.randomUUID(), username, normalizedUsername, email, normalizedEmail, passwordHash,
                false, UserRole.USER, true, true, now, now, 0);
    }

    public static UserAccount bootstrapAdmin(
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String passwordHash,
            Instant now) {
        return new UserAccount(
                UUID.randomUUID(), username, normalizedUsername, email, normalizedEmail, passwordHash,
                true, UserRole.ADMIN, true, true, now, now, 0);
    }

    public static UserAccount rehydrate(
            UUID id,
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            String passwordHash,
            boolean emailVerified,
            UserRole role,
            boolean notifyOnJobCompletion,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new UserAccount(id, username, normalizedUsername, email, normalizedEmail, passwordHash,
                emailVerified, role, notifyOnJobCompletion, enabled, createdAt, updatedAt, version);
    }

    public void verifyEmail(Instant now) {
        emailVerified = true;
        updatedAt = Objects.requireNonNull(now);
    }

    public void changePassword(String encodedPassword, Instant now) {
        passwordHash = requireText(encodedPassword, "encodedPassword");
        updatedAt = Objects.requireNonNull(now);
    }

    public void updateNotificationPreference(boolean enabled, Instant now) {
        notifyOnJobCompletion = enabled;
        updatedAt = Objects.requireNonNull(now);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public UUID id() { return id; }
    public String username() { return username; }
    public String normalizedUsername() { return normalizedUsername; }
    public String email() { return email; }
    public String normalizedEmail() { return normalizedEmail; }
    public String passwordHash() { return passwordHash; }
    public boolean emailVerified() { return emailVerified; }
    public UserRole role() { return role; }
    public boolean notifyOnJobCompletion() { return notifyOnJobCompletion; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
