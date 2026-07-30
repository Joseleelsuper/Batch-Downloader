package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;

/**
 * Define el contrato de {@code IdentityTokenStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface IdentityTokenStore {
    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param token Token utilizado para autorizar o correlacionar la operación.
     * @return Resultado producido por {@code save}.
     */
    IdentityToken save(IdentityToken token);
    /**
     * Busca el resultado solicitado mediante {@code findByHashAndType}.
     *
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Resultado producido por {@code findByHashAndType}.
     */
    Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type);
    /**
     * Ejecuta la operación {@code invalidateUnconsumedForUser}.
     *
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     */
    void invalidateUnconsumedForUser(java.util.UUID userId, IdentityToken.Type type);
}
