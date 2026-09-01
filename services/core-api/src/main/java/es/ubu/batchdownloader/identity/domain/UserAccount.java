package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementa el componente {@code UserAccount}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class UserAccount {
    /**
     * Estado {@code id} mantenido por {@code UserAccount}.
     */
    private final UUID id;
    /**
     * Estado {@code username} mantenido por {@code UserAccount}.
     */
    private String username;
    /**
     * Estado {@code normalizedUsername} mantenido por {@code UserAccount}.
     */
    private String normalizedUsername;
    /**
     * Estado {@code email} mantenido por {@code UserAccount}.
     */
    private final String email;
    /**
     * Estado {@code normalizedEmail} mantenido por {@code UserAccount}.
     */
    private final String normalizedEmail;
    /**
     * Estado {@code passwordHash} mantenido por {@code UserAccount}.
     */
    private String passwordHash;
    /**
     * Estado {@code emailVerified} mantenido por {@code UserAccount}.
     */
    private boolean emailVerified;
    /**
     * Estado {@code role} mantenido por {@code UserAccount}.
     */
    private final UserRole role;
    /**
     * Estado {@code notifyOnJobCompletion} mantenido por {@code UserAccount}.
     */
    private boolean notifyOnJobCompletion;
    /**
     * Estado {@code enabled} mantenido por {@code UserAccount}.
     */
    private boolean enabled;
    /**
     * Estado {@code createdAt} mantenido por {@code UserAccount}.
     */
    private final Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code UserAccount}.
     */
    private Instant updatedAt;
    /**
     * Estado {@code version} mantenido por {@code UserAccount}.
     */
    private long version;

    /**
     * Inicializa una instancia de {@code UserAccount}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @param emailVerified Valor de {@code emailVerified} utilizado por la operación.
     * @param role Valor de {@code role} utilizado por la operación.
     * @param notifyOnJobCompletion Valor de {@code notifyOnJobCompletion} utilizado por la
     *     operación.
     * @param enabled Valor de {@code enabled} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param version Valor de {@code version} utilizado por la operación.
     */
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

    /**
     * Ejecuta la operación {@code register}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Resultado producido por {@code register}.
     */
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

    /**
     * Ejecuta la operación {@code bootstrapAdmin}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Resultado producido por {@code bootstrapAdmin}.
     */
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

    /**
     * Ejecuta la operación {@code rehydrate}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @param emailVerified Valor de {@code emailVerified} utilizado por la operación.
     * @param role Valor de {@code role} utilizado por la operación.
     * @param notifyOnJobCompletion Valor de {@code notifyOnJobCompletion} utilizado por la
     *     operación.
     * @param enabled Valor de {@code enabled} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param version Valor de {@code version} utilizado por la operación.
     * @return Resultado producido por {@code rehydrate}.
     */
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

    /**
     * Verifica los datos recibidos mediante {@code verifyEmail}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void verifyEmail(Instant now) {
        emailVerified = true;
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Ejecuta la operación {@code changePassword}.
     *
     * @param encodedPassword Valor de {@code encodedPassword} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void changePassword(String encodedPassword, Instant now) {
        passwordHash = requireText(encodedPassword, "encodedPassword");
        updatedAt = Objects.requireNonNull(now);
    }

    /** Actualiza el nombre visible sin alterar la identidad estable del propietario. */
    public void changeUsername(String value, String normalizedValue, Instant now) {
        username = requireText(value, "username");
        normalizedUsername = requireText(normalizedValue, "normalizedUsername");
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateNotificationPreference}.
     *
     * @param enabled Valor de {@code enabled} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void updateNotificationPreference(boolean enabled, Instant now) {
        notifyOnJobCompletion = enabled;
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Ejecuta la operación {@code requireText}.
     *
     * @param value Valor que debe procesarse.
     * @param field Valor de {@code field} utilizado por la operación.
     * @return Resultado producido por {@code requireText}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    public UUID id() { return id; }
    /**
     * Ejecuta la operación {@code username}.
     *
     * @return Resultado producido por {@code username}.
     */
    public String username() { return username; }
    /**
     * Normaliza el valor recibido mediante {@code normalizedUsername}.
     *
     * @return Resultado producido por {@code normalizedUsername}.
     */
    public String normalizedUsername() { return normalizedUsername; }
    /**
     * Ejecuta la operación {@code email}.
     *
     * @return Resultado producido por {@code email}.
     */
    public String email() { return email; }
    /**
     * Normaliza el valor recibido mediante {@code normalizedEmail}.
     *
     * @return Resultado producido por {@code normalizedEmail}.
     */
    public String normalizedEmail() { return normalizedEmail; }
    /**
     * Ejecuta la operación {@code passwordHash}.
     *
     * @return Resultado producido por {@code passwordHash}.
     */
    public String passwordHash() { return passwordHash; }
    /**
     * Ejecuta la operación {@code emailVerified}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean emailVerified() { return emailVerified; }
    /**
     * Ejecuta la operación {@code role}.
     *
     * @return Resultado producido por {@code role}.
     */
    public UserRole role() { return role; }
    /**
     * Ejecuta la operación {@code notifyOnJobCompletion}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean notifyOnJobCompletion() { return notifyOnJobCompletion; }
    /**
     * Ejecuta la operación {@code enabled}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean enabled() { return enabled; }
    /**
     * Crea el recurso solicitado mediante {@code createdAt}.
     *
     * @return Resultado producido por {@code createdAt}.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Actualiza el recurso solicitado mediante {@code updatedAt}.
     *
     * @return Resultado producido por {@code updatedAt}.
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Ejecuta la operación {@code version}.
     *
     * @return Resultado producido por {@code version}.
     */
    public long version() { return version; }
}
