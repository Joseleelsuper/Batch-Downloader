package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Compone consultas y escrituras de bundles y ofrece a controladores y descargas un acceso común a
 * sus políticas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.bundle.BundleReadRepository
 * @see es.ubu.batchdownloader.bundle.BundleWriteRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@Repository
public class BundleRepository {
    private final BundleReadRepository reads;
    private final BundleWriteRepository writes;

    /**
     * Conecta consultas con visibilidad y escrituras transaccionales de bundles.
     *
     * @param reads Consultas y proyecciones de bundles utilizadas después de confirmar sus datos en
     *     la transacción.
     * @param writes Operaciones transaccionales de creación, edición y borrado administrativo.
     */
    public BundleRepository(
            BundleReadRepository reads,
            BundleWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /**
     * Selecciona bundles públicos u oficiales; community incluye también los de tipo user y el
     * enriquecimiento se realiza por página.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @return resúmenes en el orden solicitado.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public List<BundleSummary> list(String type, String sort, int page, int pageSize) {
        return reads.list(type, sort, page, pageSize);
    }

    /**
     * Cuenta los mismos tipos y visibilidades que el listado público, sin paginación.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @return total público filtrado.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public long count(String type) {
        return reads.count(type);
    }

    /**
     * Selecciona una página de cualquier visibilidad y aplica el tipo literalmente, sin expandir
     * community a user.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @return resúmenes administrativos enriquecidos por lotes.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public List<BundleSummary> listForAdministration(
            String type, String sort, int page, int pageSize) {
        return reads.listForAdministration(type, sort, page, pageSize);
    }

    /**
     * Cuenta bundles de cualquier visibilidad con el filtro literal de tipo del listado
     * administrativo.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @return total administrativo filtrado.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public long countForAdministration(String type) {
        return reads.countForAdministration(type);
    }

    /**
     * Carga el detalle por UUID o slug y aplica la misma regla de visibilidad utilizada al
     * descargar.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param viewerId UUID de quien consulta, o null para visitantes anónimos.
     * @param administrator Permite al administrador consultar bundles de cualquier visibilidad.
     * @return detalle accesible.
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta el bundle o es privado para
     *     otro propietario.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public BundleDetails details(String publicId, UUID viewerId, boolean administrator) {
        return reads.details(publicId, viewerId, administrator);
    }

    /**
     * Carga un detalle sin comprobar identidad; solo debe usarse desde una operación que ya
     * autorizó el recurso.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return detalle del bundle existente.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe el UUID o slug.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public BundleDetails detailsInternal(String publicId) {
        return reads.detailsInternal(publicId);
    }

    /**
     * Consulta solo acceso e identidades de aplicaciones activas y conserva su orden; materializa
     * como máximo 101 para detectar exceso.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param viewerId UUID de quien consulta, o null para visitantes anónimos.
     * @param administrator Permite al administrador consultar bundles de cualquier visibilidad.
     * @return hasta cien UUID de aplicaciones.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el bundle no existe o no es
     *     accesible.
     * @throws es.ubu.batchdownloader.common.ConflictException si contiene más de cien aplicaciones
     *     activas.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    public List<UUID> appIdsForDownload(
            String publicId, UUID viewerId, boolean administrator) {
        return reads.appIdsForDownload(publicId, viewerId, administrator);
    }

    /**
     * Proyecta las plataformas con al menos un instalador seleccionable del bundle.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @return plataformas en orden windows, linux, macos.
     * @see es.ubu.batchdownloader.bundle.BundleReadRepository
     */
    List<String> availableOperatingSystems(UUID bundleId) {
        return reads.availableOperatingSystems(bundleId);
    }
    /**
     * Reserva UUID y slug, guarda propietario y metadatos y sustituye etiquetas e items en la misma
     * transacción.
     *
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @return detalle creado.
     * @throws es.ubu.batchdownloader.common.ConflictException si el slug explícito está ocupado o
     *     la selección supera cien aplicaciones.
     * @see es.ubu.batchdownloader.bundle.BundleWriteRepository
     */
    public BundleDetails create(UpsertBundleRequest request, UUID ownerId) {
        return writes.create(request, ownerId);
    }

    /**
     * Conserva el slug anterior cuando no se indica otro y reemplaza metadatos, etiquetas e items
     * incrementando la versión.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @return detalle guardado.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe el bundle.
     * @throws es.ubu.batchdownloader.common.ConflictException si el nuevo slug está ocupado o la
     *     selección excede cien aplicaciones.
     * @see es.ubu.batchdownloader.bundle.BundleWriteRepository
     */
    public BundleDetails update(String publicId, UpsertBundleRequest request) {
        return writes.update(publicId, request);
    }

    /**
     * Resuelve el UUID o slug y elimina el bundle dentro de una transacción.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @see es.ubu.batchdownloader.bundle.BundleWriteRepository
     */
    public void delete(String publicId) {
        writes.delete(publicId);
    }
}
