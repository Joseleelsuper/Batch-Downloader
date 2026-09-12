package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa un permiso de un solo uso para confirmar correo o restablecer contraseña, guardando
 * únicamente su hash y vigencia.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.application.port.IdentityTokenStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public final class IdentityToken {
    /**
     * Distingue tokens de verificación de correo y recuperación para impedir su uso en un flujo
     * diferente.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    public enum Type { /**
 * Valor compartido que fija e m a i l  v e r i f i c a t i o n para el comportamiento del
 * componente.
 */
EMAIL_VERIFICATION, /**
 * Valor compartido que fija p a s s w o r d  r e s e t para el comportamiento del componente.
 */
PASSWORD_RESET }

    /**
     * UUID estable del agregado que se consulta o reconstruye.
     */
    private final UUID id;
    /**
     * UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     */
    private final UUID userId;
    /**
     * SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin almacenar su original.
     */
    private final String tokenHash;
    /**
     * Finalidad del token: verificación de correo o restablecimiento de contraseña.
     */
    private final Type type;
    /**
     * Instante a partir del cual el token deja de ser utilizable, incluido el propio límite.
     */
    private final Instant expiresAt;
    /**
     * Instante de creación original del agregado.
     */
    private final Instant createdAt;
    /**
     * Instante de consumo o invalidación; null significa que aún no se ha consumido.
     */
    private Instant consumedAt;
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     */
    private long version;

    /**
     * Reconstruye identidad, finalidad y fechas no nulas del token conservando su consumo y versión
     * persistida.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param expiresAt Instante a partir del cual el token deja de ser utilizable, incluido el
     *     propio límite.
     * @param consumedAt Instante de consumo o invalidación; null significa que aún no se ha
     *     consumido.
     * @param createdAt Instante de creación original del agregado.
     * @param version Versión persistida utilizada para detectar escrituras concurrentes.
     */
    private IdentityToken(
            UUID id, UUID userId, String tokenHash, Type type, Instant expiresAt, Instant consumedAt,
            Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.type = Objects.requireNonNull(type);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    /**
     * Crea un token pendiente con UUID aleatorio, fechas del llamador y versión inicial cero.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param expiresAt Instante a partir del cual el token deja de ser utilizable, incluido el
     *     propio límite.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     * @return token nuevo sin consumir.
     */
    public static IdentityToken issue(UUID userId, String tokenHash, Type type, Instant expiresAt, Instant now) {
        return new IdentityToken(UUID.randomUUID(), userId, tokenHash, type, expiresAt, null, now, 0);
    }

    /**
     * Reconstruye un token almacenado sin renovar su vigencia ni borrar su consumo.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param expiresAt Instante a partir del cual el token deja de ser utilizable, incluido el
     *     propio límite.
     * @param consumedAt Instante de consumo o invalidación; null significa que aún no se ha
     *     consumido.
     * @param createdAt Instante de creación original del agregado.
     * @param version Versión persistida utilizada para detectar escrituras concurrentes.
     * @return agregado con la versión persistida.
     */
    public static IdentityToken rehydrate(
            UUID id, UUID userId, String tokenHash, Type type, Instant expiresAt, Instant consumedAt,
            Instant createdAt, long version) {
        return new IdentityToken(id, userId, tokenHash, type, expiresAt, consumedAt, createdAt, version);
    }

    /**
     * Exige ausencia de consumo y vencimiento estrictamente posterior al instante consultado.
     *
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     * @return true únicamente mientras el token siga pendiente y vigente.
     */
    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Marca el uso del token con el instante indicado después de comprobar su vigencia.
     *
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     * @throws IllegalStateException si ya fue consumido o alcanzó su vencimiento.
     */
    public void consume(Instant now) {
        if (!usableAt(now)) throw new IllegalStateException("token_not_usable");
        consumedAt = now;
    }

    /**
     * UUID estable del agregado que se consulta o reconstruye.
     *
     * @return UUID estable del agregado que se consulta o reconstruye.
     */
    public UUID id() { return id; }
    /**
     * UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     *
     * @return UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     */
    public UUID userId() { return userId; }
    /**
     * SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin almacenar su original.
     *
     * @return SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin almacenar su
     *     original.
     */
    public String tokenHash() { return tokenHash; }
    /**
     * Finalidad del token: verificación de correo o restablecimiento de contraseña.
     *
     * @return Finalidad del token: verificación de correo o restablecimiento de contraseña.
     */
    public Type type() { return type; }
    /**
     * Instante a partir del cual el token deja de ser utilizable, incluido el propio límite.
     *
     * @return Instante a partir del cual el token deja de ser utilizable, incluido el propio
     *     límite.
     */
    public Instant expiresAt() { return expiresAt; }
    /**
     * Instante de consumo o invalidación; null significa que aún no se ha consumido.
     *
     * @return Instante de consumo o invalidación; null significa que aún no se ha consumido.
     */
    public Instant consumedAt() { return consumedAt; }
    /**
     * Instante de creación original del agregado.
     *
     * @return Instante de creación original del agregado.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Versión persistida utilizada para detectar escrituras concurrentes.
     *
     * @return Versión persistida utilizada para detectar escrituras concurrentes.
     */
    public long version() { return version; }
}
