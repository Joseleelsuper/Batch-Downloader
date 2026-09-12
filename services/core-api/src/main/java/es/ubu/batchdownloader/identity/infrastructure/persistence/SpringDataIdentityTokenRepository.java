package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;

/**
 * Define consultas JPA de tokens y un bloqueo de escritura que serializa su consumo dentro de una
 * transacción.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.IdentityTokenEntity
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.JpaIdentityTokenStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
interface SpringDataIdentityTokenRepository extends JpaRepository<IdentityTokenEntity, UUID> {
    /**
     * Consulta un token por su hash y finalidad sin bloquearlo para consumo.
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return entidad coincidente o vacío; no comprueba vigencia.
     */
    Optional<IdentityTokenEntity> findByTokenHashAndType(String tokenHash, IdentityToken.Type type);

    /**
     * Adquiere un bloqueo pesimista de escritura sobre el token coincidente hasta el fin de la
     * transacción.
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return entidad reservada o vacío si no existe.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from IdentityTokenEntity token where token.tokenHash = :hash and token.type = :type")
    Optional<IdentityTokenEntity> findForUpdate(
            @Param("hash") String tokenHash, @Param("type") IdentityToken.Type type);
    /**
     * Invalida en lote los tokens aún pendientes de la cuenta y finalidad guardando el instante
     * indicado como consumo.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    @Modifying
    @Query("update IdentityTokenEntity token set token.consumedAt = :now "
            + "where token.userId = :userId and token.type = :type and token.consumedAt is null")
    void consumeUnconsumed(
            @Param("userId") UUID userId,
            @Param("type") IdentityToken.Type type,
            @Param("now") Instant now);
}
