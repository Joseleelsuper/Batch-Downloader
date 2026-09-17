package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.time.Instant;

/**
 * Persiste hashes de tokens y ofrece la reserva que impide consumir dos veces el mismo permiso.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.IdentityToken
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public interface IdentityTokenStore {
    /**
     * Inserta o actualiza un token en la transacción del caso de uso.
     *
     * @param token Agregado de token que conserva hash, finalidad, vencimiento y consumo.
     * @return token persistido con su versión de concurrencia.
     */
    IdentityToken save(IdentityToken token);
    /**
     * Localiza un token por hash y finalidad sin reservarlo para consumo.
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return token coincidente o vacío; su vigencia debe comprobarla el caso de uso.
     */
    Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type);
    /**
     * Localiza y bloquea el token para serializar comprobación y consumo en la transacción del
     * llamador.
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return token reservado o vacío si no existe.
     */
    Optional<IdentityToken> findByHashAndTypeForUpdate(String tokenHash, IdentityToken.Type type);
    /**
     * Marca consumidos los tokens pendientes de la cuenta y finalidad al emitir un reemplazo.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    void invalidateUnconsumedForUser(java.util.UUID userId, IdentityToken.Type type, Instant now);
}
