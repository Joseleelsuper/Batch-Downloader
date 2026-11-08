package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import java.util.Locale;

/**
 * Agrupa los filtros de una misma consulta para compartir su significado entre listados,
 * totales y facetas.
 *
 * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
 * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
 *     estado público.
 * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
 *     plataformas.
 * @param architecture Arquitectura opcional que debe existir entre las fuentes de la aplicación.
 * @param tags Etiquetas seleccionadas, sin distinguir mayúsculas; minimumTags fija cuántas se exigen.
 * @param publishers Editores normalizados alternativos; una aplicación debe coincidir con alguno.
 * @param minimumTags Mínimo de etiquetas distintas; null exige todas las seleccionadas.
 * @see es.ubu.batchdownloader.catalog.CatalogFilterSql
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public record CatalogQuery(
        String query, String status, List<String> operatingSystems, String architecture,
        List<String> tags, List<String> publishers, Integer minimumTags) {
    public CatalogQuery(String query, String status, List<String> operatingSystems,
            String architecture, List<String> tags, List<String> publishers) {
        this(query, status, operatingSystems, architecture, tags, publishers, null);
    }
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
     * @param minimumTags Mínimo explícito de una a tantas etiquetas distintas como se seleccionen.
     */
    public CatalogQuery {
        status = CatalogRepository.normalizeCatalogStatus(status);
        long tagCount = tags == null ? 0 : tags.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT)).distinct().count();
        if (minimumTags != null && (minimumTags < 1 || minimumTags > tagCount)) {
            throw new BadRequestException("invalid_minimum_tags", "El mínimo debe estar entre una y el número de etiquetas seleccionadas.");
        }
    }
}
