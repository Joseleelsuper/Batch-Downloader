package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Gestiona la persistencia y consulta de {@code BundleRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class BundleRepository {
    private final BundleReadRepository reads;
    private final BundleWriteRepository writes;

    /**
     * Inicializa una instancia de {@code BundleRepository}.
     *
     * @param reads consultas y autorización de lectura
     * @param writes mutaciones transaccionales
     */
    public BundleRepository(
            BundleReadRepository reads,
            BundleWriteRepository writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /**
     * Enumera los elementos solicitados mediante {@code list}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<BundleSummary> list(String type, String sort, int page, int pageSize) {
        return reads.list(type, sort, page, pageSize);
    }

    /** Cuenta bundles visibles en el catálogo público. */
    public long count(String type) {
        return reads.count(type);
    }

    /** Devuelve una página administrativa sin ocultar bundles privados. */
    public List<BundleSummary> listForAdministration(
            String type, String sort, int page, int pageSize) {
        return reads.listForAdministration(type, sort, page, pageSize);
    }

    /** Cuenta bundles para la vista administrativa. */
    public long countForAdministration(String type) {
        return reads.countForAdministration(type);
    }

    /** Devuelve un detalle solamente cuando la política permite verlo. */
    public BundleDetails details(String publicId, UUID viewerId, boolean administrator) {
        return reads.details(publicId, viewerId, administrator);
    }

    /** Obtiene un detalle interno después de que el caso de uso haya autorizado la operación. */
    public BundleDetails detailsInternal(String publicId) {
        return reads.detailsInternal(publicId);
    }

    /** Resuelve las aplicaciones descargables respetando visibilidad y sistema operativo. */
    public List<UUID> appIdsForDownload(
            String publicId, UUID viewerId, boolean administrator) {
        return reads.appIdsForDownload(publicId, viewerId, administrator);
    }

    /** Devuelve los sistemas con al menos un instalador seleccionable. */
    List<String> availableOperatingSystems(UUID bundleId) {
        return reads.availableOperatingSystems(bundleId);
    }
    /** Crea un bundle con propietario UUID canónico. */
    public BundleDetails create(UpsertBundleRequest request, UUID ownerId) {
        return writes.create(request, ownerId);
    }

    /** Actualiza el bundle y reemplaza sus relaciones de forma transaccional. */
    public BundleDetails update(String publicId, UpsertBundleRequest request) {
        return writes.update(publicId, request);
    }

    /** Elimina un bundle y sus relaciones. */
    public void delete(String publicId) {
        writes.delete(publicId);
    }
}
