package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogAlphabetEntry;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Coordina búsqueda léxica o por candidatos semánticos, filtros MySQL y enriquecimiento por lotes
 * conservando el orden y los estados públicos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogQuery
 * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
 * @see es.ubu.batchdownloader.catalog.CatalogFacetRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Repository
public class CatalogRepository {
    /**
     * Valor compartido que fija r e v i e w  l a s t  o r d e r para el comportamiento del
     * componente.
     */
    private static final String REVIEW_LAST_ORDER =
            "a.catalog_review_priority ASC";
    /**
     * Valor compartido que representa c a t a l o g  s t a t u s e s en el contrato del módulo.
     */
    private static final Set<String> CATALOG_STATUSES =
            Set.of("all", "available", "review", "missing", "unresolved");

    /**
     * Estado {@code jdbc} mantenido por {@code CatalogRepository}.
     */
    private final JdbcTemplate jdbc;
    private final CatalogStatisticsRepository statistics;
    private final CatalogProjectionRepository projections;
    private final CatalogFacetRepository facetRepository;

    /**
     * Compone SQL de búsqueda, proyecciones, facetas y estadísticas sin duplicar el enriquecimiento
     * de aplicaciones.
     *
     * @param jdbc Acceso SQL a catálogo, fuentes y proyecciones persistidas en MySQL.
     * @param statistics Consulta de contadores y versiones de invalidación del catálogo.
     * @param projections Enriquecimiento común por lotes de aplicaciones, fuentes, etiquetas y
     *     plataformas.
     * @param facetRepository Consultas de etiquetas, editores e índice alfabético bajo los mismos
     *     filtros.
     */
    public CatalogRepository(
            JdbcTemplate jdbc,
            CatalogStatisticsRepository statistics,
            CatalogProjectionRepository projections,
            CatalogFacetRepository facetRepository) {
        this.jdbc = jdbc;
        this.statistics = statistics;
        this.projections = projections;
        this.facetRepository = facetRepository;
    }

    /**
     * Selecciona una página léxica o semántica según el modo ya resuelto y enriquece sus UUID
     * conservando el orden SQL.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param sort name ordena por nombre; updated y downloads priorizan su fecha o contador antes
     *     del desempate de relevancia.
     * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return aplicaciones activas de la página; omite las que desaparecieron durante el
     *     enriquecimiento.
     */
    public List<AppListItem> search(CatalogQuery filters, String sort, int page, int pageSize, SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticSearch(filters, sort, page, pageSize, candidates);
        }
        SearchRanking ranking = SearchRanking.from(filters.query());
        StringBuilder sql = new StringBuilder("SELECT a.id");
        List<Object> params = new ArrayList<>();
        if (ranking.active()) {
            sql.append(", ").append(ranking.scoreSql()).append(" AS search_score");
            params.addAll(ranking.params());
        }
        sql.append("""

                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendAll(sql, params, filters);
        sql.append(" ORDER BY ").append(orderBy(sort, ranking.innerPrefix()));
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<UUID> appIds = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                params.toArray());
        return loadPage(appIds);
    }

    /**
     * Cuenta el mismo conjunto filtrado de la búsqueda sin paginación y respeta el modo aplicado.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return total de aplicaciones activas coincidentes.
     */
    public long count(CatalogQuery filters, SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticCount(filters, candidates);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        CatalogFilterSql.appendAll(sql, params, filters);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /**
     * Delega el índice alfabético conservando filtros, tamaño de página y candidatos de la misma
     * búsqueda.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return grupos alfabéticos con primera página y recuento.
     */
    public List<CatalogAlphabetEntry> alphabet(CatalogQuery filters, int pageSize, SemanticCandidateSet candidates) {
        return facetRepository.alphabet(filters, pageSize, candidates);
    }

    /**
     * Delega el cálculo de facetas con los mismos filtros y candidatos que producen los resultados.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return etiquetas y editores con sus recuentos.
     */
    public CatalogFacetsResponse facets(CatalogQuery filters, SemanticCandidateSet candidates) {
        return facetRepository.facets(filters, candidates);
    }
    /**
     * Cruza únicamente los candidatos de embeddings con aplicaciones activas y aplica filtros
     * estructurados y orden solicitado sin añadir candidatos léxicos.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param sort name ordena por nombre; updated y downloads priorizan su fecha o contador antes
     *     del desempate de relevancia.
     * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return página enriquecida del conjunto semántico filtrado.
     */
    private List<AppListItem> semanticSearch(CatalogQuery filters, String sort, int page, int pageSize, SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(filters.query(), candidates, params));
        sql.append("""
                SELECT a.id, ranked.semantic_rank
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendStructured(sql, params, filters);
        sql.append(" ORDER BY ")
                .append(orderBy(sort, "ranked.semantic_rank ASC, "))
                .append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<UUID> appIds = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                params.toArray());
        return loadPage(appIds);
    }

    /**
     * Enriquece la página en lote y reconstruye su orden a partir de los UUID de la búsqueda.
     *
     * @param orderedIds UUID de la página en el orden determinado por la consulta de búsqueda.
     * @return proyecciones disponibles en el orden original.
     */
    private List<AppListItem> loadPage(List<UUID> orderedIds) {
        Map<UUID, AppListItem> itemsById = projections.listItems(orderedIds);
        return orderedIds.stream()
                .map(itemsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Cuenta candidatos semánticos que siguen activos y cumplen todos los filtros estructurados.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return total semántico filtrado antes de paginar.
     */
    private long semanticCount(CatalogQuery filters, SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(filters.query(), candidates, params));
        sql.append("""
                SELECT COUNT(*)
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendStructured(sql, params, filters);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /**
     * Resuelve UUID, slug o paquete y exige que la aplicación esté activa antes de construir el
     * detalle.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return vista pública con fuentes exactas y procedencia.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no se encuentra la identidad o la
     *     aplicación no está activa.
     * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
     */
    public AppDetails details(String publicId) {
        return projections.details(publicId);
    }

    /**
     * Deduplica UUID y carga metadatos y enriquecimiento con hasta cuatro consultas por lote,
     * independientemente del número solicitado.
     *
     * @param requestedIds UUID solicitados; null, listas vacías y entradas nulas se omiten.
     * @return mapa inmutable de aplicaciones activas por UUID; omite nulos y ausentes.
     * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
     */
    public Map<UUID, AppListItem> listItems(Collection<UUID> requestedIds) {
        return projections.listItems(requestedIds);
    }

    /**
     * Resuelve UUID textual, slug o identificador Winstall sin exigir que la aplicación esté
     * activa.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return UUID persistido de la aplicación.
     * @throws es.ubu.batchdownloader.common.NotFoundException si ninguna identidad coincide.
     * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
     */
    public UUID softwareAppId(String publicId) {
        return projections.softwareAppId(publicId);
    }

    /**
     * Resuelve una identidad de aplicación únicamente dentro del catálogo activo que puede
     * seleccionar un usuario.
     *
     * @param publicId UUID textual, slug o identificador Winstall de la aplicación.
     * @return UUID de una aplicación activa.
     * @throws es.ubu.batchdownloader.common.NotFoundException si la identidad no existe o la
     *     aplicación está inactiva.
     * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
     */
    public UUID publicSoftwareAppId(String publicId) {
        return projections.publicSoftwareAppId(publicId);
    }
    /**
     * Lee los totales proyectados por estado y añade última ejecución y fecha UTC de consulta.
     *
     * @return estadísticas públicas sin contar de nuevo cada aplicación.
     * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
     */
    public CatalogStatsResponse stats() {
        return statistics.stats();
    }

    /**
     * Combina la versión persistida de catalog_counters con sus cuatro recuentos para invalidar
     * respuestas del catálogo.
     *
     * @return token textual de versión y cantidades.
     * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
     */
    public String cacheVersion() {
        return statistics.cacheVersion();
    }

    /**
     * Construye un evento catalog.changed con versión compuesta y fecha UTC actual.
     *
     * @return evento para invalidar las consultas del cliente.
     * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
     */
    public CatalogChangeEvent changeEvent() {
        return statistics.changeEvent();
    }

    /**
     * Combina cantidad y actualización de aplicaciones activas, contadores de catálogo y progreso
     * de la última ejecución en un hash textual.
     *
     * @return token opaco de cambio; no es una huella criptográfica.
     * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
     */
    public String changeVersion() {
        return statistics.changeVersion();
    }

    /**
     * Da prioridad al orden solicitado y utiliza relevancia y UUID para desempatar; salvo en orden
     * name coloca revisión al final.
     *
     * @param sort name ordena por nombre; updated y downloads priorizan su fecha o contador antes
     *     del desempate de relevancia.
     * @param relevancePrefix Fragmento de orden de relevancia ya construido por código, nunca
     *     recibido del cliente.
     * @return fragmento ORDER BY construido solo con alternativas controladas.
     */
    private String orderBy(String sort, String relevancePrefix) {
        String relevanceOrder =
                relevancePrefix == null || relevancePrefix.isBlank() ? "" : relevancePrefix;
        String selectedOrder = switch (sort) {
            case "updated" ->
                    "a.updated_at DESC, " + relevanceOrder + "a.normalized_name ASC, a.id ASC";
            case "downloads" ->
                    "a.download_count DESC, " + relevanceOrder + "a.normalized_name ASC, a.id ASC";
            default -> "a.normalized_name ASC, " + relevanceOrder + "a.id ASC";
        };
        return "name".equals(sort) ? selectedOrder : reviewLastOrder() + ", " + selectedOrder;
    }

    /**
     * Proporciona la expresión común que pospone aplicaciones pendientes de revisión.
     *
     * @return expresión SQL constante de orden por estado.
     */
    private String reviewLastOrder() {
        return REVIEW_LAST_ORDER;
    }

    /**
     * Retira diacríticos, convierte a minúsculas y agrupa espacios para comparar nombres y
     * etiquetas.
     *
     * @param value Texto que se normaliza o clasifica según el método.
     * @return consulta normalizada o cadena vacía para ausencia.
     */
    static String normalizeSearchQuery(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    /**
     * Normaliza los estados conocidos y permite unresolved como agrupación administrativa de review
     * y missing.
     *
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @return estado validado; all cuando no se proporciona.
     * @throws es.ubu.batchdownloader.common.BadRequestException si no se reconoce el estado.
     */
    static String normalizeCatalogStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? "all"
                : status.trim().toLowerCase(Locale.ROOT);
        if (!CATALOG_STATUSES.contains(normalized)) {
            throw new BadRequestException(
                    "invalid_catalog_status",
                    "El estado de catálogo indicado no es válido.");
        }
        return normalized;
    }

    /**
     * Elimina todos los grupos de espacios de una consulta ya normalizada.
     *
     * @param normalized Consulta sin diacríticos, recortada y en minúsculas.
     * @return texto compacto o cadena vacía para null.
     */
    static String compactSearchQuery(String normalized) {
        return normalized == null ? "" : normalized.replaceAll("\\s+", "");
    }

    /**
     * Conserva la expresión de puntuación léxica y sus parámetros en orden, o representa una
     * búsqueda sin texto.
     *
     * @param active La búsqueda contiene texto no vacío y debe calcular puntuación léxica.
     * @param scoreSql Expresión SQL parametrizada que combina coincidencia exacta, prefijos,
     *     contenido y tokens.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    record SearchRanking(boolean active, String scoreSql, List<Object> params) {
        /**
         * Puntúa coincidencias exactas, prefijos y contenido de nombre, editor, paquete y
         * etiquetas; añade hasta seis tokens y pesos menores para descripciones.
         *
         * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita
         *     embeddings.
         * @return ranking activo parametrizado o ranking vacío si no hay texto.
         */
        static SearchRanking from(String query) {
            String normalized = normalizeSearchQuery(query);
            if (normalized.isBlank()) {
                return new SearchRanking(false, "", List.of());
            }
            String compact = compactSearchQuery(normalized);
            String lowerRaw = query.toLowerCase(Locale.ROOT).trim();
            String normalizedPrefix = normalized + "%";
            String normalizedContains = "%" + normalized + "%";
            String compactPrefix = compact + "%";
            String compactContains = "%" + compact + "%";
            String rawPrefix = lowerRaw + "%";
            String rawContains = "%" + lowerRaw + "%";

            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    (
                        CASE WHEN a.normalized_name = ? THEN 10000 ELSE 0 END
                      + CASE WHEN a.normalized_name LIKE ? THEN 9000 ELSE 0 END
                      + CASE WHEN a.normalized_name LIKE ? THEN 7600 ELSE 0 END
                      + CASE WHEN REPLACE(a.normalized_name, ' ', '') = ? THEN 7300 ELSE 0 END
                      + CASE WHEN REPLACE(a.normalized_name, ' ', '') LIKE ? THEN 6800 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) = ? THEN 3400 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) LIKE ? THEN 2600 ELSE 0 END
                      + CASE WHEN LOWER(a.winstall_id) LIKE ? THEN 2200 ELSE 0 END
                      + CASE WHEN LOWER(REPLACE(a.winstall_id, '.', '')) LIKE ? THEN 2200 ELSE 0 END
                      + CASE WHEN EXISTS (
                            SELECT 1 FROM software_app_tags sat_rank_exact
                            WHERE sat_rank_exact.software_app_id = a.id
                              AND sat_rank_exact.normalized_tag = ?
                        ) THEN 1700 ELSE 0 END
                      + CASE WHEN EXISTS (
                            SELECT 1 FROM software_app_tags sat_rank_like
                            WHERE sat_rank_like.software_app_id = a.id
                              AND sat_rank_like.normalized_tag LIKE ?
                        ) THEN 900 ELSE 0 END
                      + CASE WHEN LOWER(COALESCE(a.description, '')) LIKE ? THEN 250 ELSE 0 END
                      + CASE WHEN LOWER(COALESCE(a.long_description, '')) LIKE ? THEN 150 ELSE 0 END
                    """);
            params.addAll(List.of(normalized, normalizedPrefix, normalizedContains,
                    compact, compactPrefix, lowerRaw, rawPrefix, rawContains,
                    compactContains, normalized, normalizedContains, rawContains, rawContains));

            for (String token : searchTokens(normalized)) {
                sql.append("""
                      + CASE WHEN a.normalized_name LIKE ? THEN 80 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) LIKE ? THEN 25 ELSE 0 END
                    """);
                String tokenLike = "%" + token + "%";
                params.add(tokenLike);
                params.add(tokenLike);
            }
            sql.append(")");
            return new SearchRanking(true, sql.toString(), List.copyOf(params));
        }

        /**
         * Activa el desempate por puntuación solo cuando existe una consulta textual.
         *
         * @return search_score DESC seguido de coma, o cadena vacía.
         */
        String innerPrefix() {
            return active ? "search_score DESC, " : "";
        }

    }

    /**
     * Conserva hasta seis tokens distintos de al menos dos caracteres en el orden de la consulta.
     *
     * @param normalized Consulta sin diacríticos, recortada y en minúsculas.
     * @return tokens usados para los pesos parciales del ranking.
     */
    private static List<String> searchTokens(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
            if (tokens.size() >= 6) {
                break;
            }
        }
        return List.copyOf(tokens);
    }

}
