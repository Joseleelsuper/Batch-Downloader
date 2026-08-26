package es.ubu.batchdownloader.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementa el componente {@code IdentityToken}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class IdentityToken {
    /**
     * Enumera los valores admitidos por {@code Type}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public enum Type { /**
 * Constante que define {@code EMAIL_VERIFICATION}.
 */
EMAIL_VERIFICATION, /**
 * Constante que define {@code PASSWORD_RESET}.
 */
PASSWORD_RESET }

    /**
     * Estado {@code id} mantenido por {@code IdentityToken}.
     */
    private final UUID id;
    /**
     * Estado {@code userId} mantenido por {@code IdentityToken}.
     */
    private final UUID userId;
    /**
     * Estado {@code tokenHash} mantenido por {@code IdentityToken}.
     */
    private final String tokenHash;
    /**
     * Estado {@code type} mantenido por {@code IdentityToken}.
     */
    private final Type type;
    /**
     * Estado {@code expiresAt} mantenido por {@code IdentityToken}.
     */
    private final Instant expiresAt;
    /**
     * Estado {@code createdAt} mantenido por {@code IdentityToken}.
     */
    private final Instant createdAt;
    /**
     * Estado {@code consumedAt} mantenido por {@code IdentityToken}.
     */
    private Instant consumedAt;
    private long version;

    /**
     * Inicializa una instancia de {@code IdentityToken}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @param consumedAt Valor de {@code consumedAt} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
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
     * Indica si se cumple la condición mediante {@code issue}.
     *
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    public static IdentityToken issue(UUID userId, String tokenHash, Type type, Instant expiresAt, Instant now) {
        return new IdentityToken(UUID.randomUUID(), userId, tokenHash, type, expiresAt, null, now, 0);
    }

    /**
     * Ejecuta la operación {@code rehydrate}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @param consumedAt Valor de {@code consumedAt} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @return Resultado producido por {@code rehydrate}.
     */
    public static IdentityToken rehydrate(
            UUID id, UUID userId, String tokenHash, Type type, Instant expiresAt, Instant consumedAt,
            Instant createdAt, long version) {
        return new IdentityToken(id, userId, tokenHash, type, expiresAt, consumedAt, createdAt, version);
    }

    /**
     * Ejecuta la operación {@code usableAt}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean usableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    /**
     * Ejecuta la operación {@code consume}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    public void consume(Instant now) {
        if (!usableAt(now)) throw new IllegalStateException("token_not_usable");
        consumedAt = now;
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    public UUID id() { return id; }
    /**
     * Ejecuta la operación {@code userId}.
     *
     * @return Resultado producido por {@code userId}.
     */
    public UUID userId() { return userId; }
    /**
     * Convierte el valor recibido mediante {@code tokenHash}.
     *
     * @return Resultado producido por {@code tokenHash}.
     */
    public String tokenHash() { return tokenHash; }
    /**
     * Ejecuta la operación {@code type}.
     *
     * @return Resultado producido por {@code type}.
     */
    public Type type() { return type; }
    /**
     * Ejecuta la operación {@code expiresAt}.
     *
     * @return Resultado producido por {@code expiresAt}.
     */
    public Instant expiresAt() { return expiresAt; }
    /**
     * Ejecuta la operación {@code consumedAt}.
     *
     * @return Resultado producido por {@code consumedAt}.
     */
    public Instant consumedAt() { return consumedAt; }
    /**
     * Crea el recurso solicitado mediante {@code createdAt}.
     *
     * @return Resultado producido por {@code createdAt}.
     */
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
