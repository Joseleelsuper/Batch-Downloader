package es.ubu.batchdownloader.catalog;

import java.util.List;

/**
 * Agrupa los seis filtros de una misma consulta para compartir su significado entre listados,
 * totales y facetas.
 *
 * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
 * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
 *     estado público.
 * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
 *     plataformas.
 * @param architecture Arquitectura opcional que debe existir entre las fuentes de la aplicación.
 * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir mayúsculas.
 * @param publishers Editores normalizados alternativos; una aplicación debe coincidir con alguno.
 * @see es.ubu.batchdownloader.catalog.CatalogFilterSql
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public record CatalogQuery(
        String query, String status, List<String> operatingSystems, String architecture,
        List<String> tags, List<String> publishers) {
    /**
     * Normaliza y valida el estado del catálogo; conserva el resto de filtros recibidos del
     * controlador.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @param publishers Editores normalizados alternativos; una aplicación debe coincidir con
     *     alguno.
     */
    public CatalogQuery {
        status = CatalogRepository.normalizeCatalogStatus(status);
    }
}
