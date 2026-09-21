package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Conserva la identidad UUID de una cuenta y sus invariantes de identidad y rol sin depender de
 * frameworks.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.application.port.UserAccountStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public final class UserAccount {
    /**
     * UUID estable del agregado que se consulta o reconstruye.
     */
    private final UUID id;
    /**
     * Nombre visible de la cuenta, distinto de su UUID de identidad.
     */
    private String username;
    /**
     * Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     */
    private String normalizedUsername;
    /**
     * Correo de la cuenta; se conserva recortado y se compara mediante su versión normalizada.
     */
    private final String email;
    /**
     * Correo recortado y en minúsculas para consulta y unicidad.
     */
    private final String normalizedEmail;
    /**
     * Hash de contraseña almacenado, nunca la contraseña original.
     */
    private String passwordHash;
    /**
     * Indica que se ha confirmado el control del correo de la cuenta.
     */
    private boolean emailVerified;
    /**
     * Rol USER o ADMIN que determina el acceso permitido.
     */
    private final UserRole role;
    /**
     * Indica que la cuenta está habilitada para autenticarse y utilizar sus recursos.
     */
    private boolean enabled;
    /**
     * Instante de creación original del agregado.
     */
    private final Instant createdAt;
    /**
     * Instante del último cambio de la cuenta.
     */
    private Instant updatedAt;
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     */
    private long version;

    /**
     * Exige identidad, rol, fechas y textos obligatorios al reconstruir una cuenta; conserva su
     * versión de concurrencia.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @param passwordHash Hash de contraseña almacenado, nunca la contraseña original.
     * @param emailVerified Indica que se ha confirmado el control del correo de la cuenta.
     * @param role Rol USER o ADMIN que determina el acceso permitido.
     * @param enabled Indica que la cuenta está habilitada para autenticarse y utilizar sus
     *     recursos.
     * @param createdAt Instante de creación original del agregado.
     * @param updatedAt Instante del último cambio de la cuenta.
     * @param version Versión persistida utilizada para detectar escrituras concurrentes.
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
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.username = requireText(username, "username");
        this.normalizedUsername = requireText(normalizedUsername, "normalizedUsername");
        this.email = requireText(email, "email");
        this.normalizedEmail = requireText(normalizedEmail, "normalizedEmail");
        this.emailVerified = emailVerified;
        this.role = Objects.requireNonNull(role);
        if (role == UserRole.ADMIN) {
            this.passwordHash = requireText(passwordHash, "passwordHash");
        } else {
            if (passwordHash != null) {
                throw new IllegalArgumentException("USER accounts cannot have a password");
            }
            this.passwordHash = null;
        }
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    /**
     * Crea una cuenta USER habilitada, pendiente de verificar el correo y sin contraseña.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     * @return cuenta nueva con UUID aleatorio y versión cero.
     */
    public static UserAccount createUser(
            String username,
            String normalizedUsername,
            String email,
            String normalizedEmail,
            Instant now) {
        return new UserAccount(
                UUID.randomUUID(), username, normalizedUsername, email, normalizedEmail, null,
                false, UserRole.USER, true, now, now, 0);
    }

    /**
     * Crea la cuenta ADMIN inicial habilitada y con correo verificado para el arranque configurado.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @param passwordHash Hash de contraseña almacenado, nunca la contraseña original.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     * @return administrador nuevo con UUID aleatorio y versión cero.
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
                true, UserRole.ADMIN, true, now, now, 0);
    }

    /**
     * Reconstruye una cuenta persistida conservando UUID, flags, fechas y versión sin aplicar
     * cambios de negocio.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @param passwordHash Hash de contraseña almacenado, nunca la contraseña original.
     * @param emailVerified Indica que se ha confirmado el control del correo de la cuenta.
     * @param role Rol USER o ADMIN que determina el acceso permitido.
     * @param enabled Indica que la cuenta está habilitada para autenticarse y utilizar sus
     *     recursos.
     * @param createdAt Instante de creación original del agregado.
     * @param updatedAt Instante del último cambio de la cuenta.
     * @param version Versión persistida utilizada para detectar escrituras concurrentes.
     * @return agregado de la cuenta.
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
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new UserAccount(id, username, normalizedUsername, email, normalizedEmail, passwordHash,
                emailVerified, role, enabled, createdAt, updatedAt, version);
    }

    /**
     * Confirma el correo y actualiza el instante de modificación de la cuenta.
     *
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    public void verifyEmail(Instant now) {
        emailVerified = true;
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Sustituye el hash administrativo por uno no vacío y registra la fecha de cambio.
     *
     * @param encodedPassword Hash calculado antes de actualizar el agregado de cuenta.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    public void changePassword(String encodedPassword, Instant now) {
        if (role != UserRole.ADMIN) throw new IllegalStateException("USER accounts have no password");
        passwordHash = requireText(encodedPassword, "encodedPassword");
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Actualiza nombre visible y clave normalizada sin cambiar la identidad UUID ni su propiedad de
     * recursos.
     *
     * @param value Nuevo nombre visible previamente validado por la política.
     * @param normalizedValue Versión normalizada del nuevo nombre para búsquedas y unicidad.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    public void changeUsername(String value, String normalizedValue, Instant now) {
        username = requireText(value, "username");
        normalizedUsername = requireText(normalizedValue, "normalizedUsername");
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Exige texto no nulo ni blanco para las invariantes internas de la cuenta, sin normalizarlo.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @param field Nombre del campo obligatorio usado para identificar una violación de invariante.
     * @return valor original validado.
     * @throws IllegalArgumentException si falta el texto obligatorio.
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    /**
     * UUID estable del agregado que se consulta o reconstruye.
     *
     * @return UUID estable del agregado que se consulta o reconstruye.
     */
    public UUID id() { return id; }
    /**
     * Nombre visible de la cuenta, distinto de su UUID de identidad.
     *
     * @return Nombre visible de la cuenta, distinto de su UUID de identidad.
     */
    public String username() { return username; }
    /**
     * Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     *
     * @return Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     */
    public String normalizedUsername() { return normalizedUsername; }
    /**
     * Correo de la cuenta; se conserva recortado y se compara mediante su versión normalizada.
     *
     * @return Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    public String email() { return email; }
    /**
     * Correo recortado y en minúsculas para consulta y unicidad.
     *
     * @return Correo recortado y en minúsculas para consulta y unicidad.
     */
    public String normalizedEmail() { return normalizedEmail; }
    /**
     * Hash de contraseña almacenado, nunca la contraseña original.
     *
     * @return Hash de contraseña almacenado, nunca la contraseña original.
     */
    public String passwordHash() { return passwordHash; }
    /**
     * Indica que se ha confirmado el control del correo de la cuenta.
     *
     * @return Indica que se ha confirmado el control del correo de la cuenta.
     */
    public boolean emailVerified() { return emailVerified; }
    /**
     * Rol USER o ADMIN que determina el acceso permitido.
     *
     * @return Rol USER o ADMIN que determina el acceso permitido.
     */
    public UserRole role() { return role; }
    /**
     * Indica que la cuenta está habilitada para autenticarse y utilizar sus recursos.
     *
     * @return Indica que la cuenta está habilitada para autenticarse y utilizar sus recursos.
     */
    public boolean enabled() { return enabled; }
    /**
     * Instante de creación original del agregado.
     *
     * @return Instante de creación original del agregado.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Instante del último cambio de la cuenta.
     *
     * @return Instante del último cambio de la cuenta.
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     *
     * @return Versión persistida utilizada para detectar escrituras concurrentes.
     */
    public long version() { return version; }
}
