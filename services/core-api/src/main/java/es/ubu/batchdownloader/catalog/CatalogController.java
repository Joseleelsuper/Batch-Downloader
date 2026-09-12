package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone consultas públicas paginadas y cacheadas con filtros coherentes, detalle de fuentes y
 * degradación semántica explícita.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @see es.ubu.batchdownloader.catalog.CatalogQuery
 * @see es.ubu.batchdownloader.catalog.SemanticSearchClient
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    /**
     * Valor compartido que fija o p e r a t i n g  s y s t e m s para el comportamiento del
     * componente.
     */
    private static final Set<String> OPERATING_SYSTEMS = Set.of("windows", "linux", "macos");
    /**
     * Valor compartido que representa p u b l i c  c a t a l o g  s t a t u s e s en el contrato
     * del módulo.
     */
    private static final Set<String> PUBLIC_CATALOG_STATUSES = Set.of("all", "available", "review", "missing");
    /**
     * Estado {@code catalog} mantenido por {@code CatalogController}.
     */
    private final CatalogRepository catalog;
    /**
     * Estado {@code semanticSearch} mantenido por {@code CatalogController}.
     */
    private final SemanticSearchClient semanticSearch;
    /** Caché breve de respuestas públicas. */
    private final PublicCatalogCache cache;

    /**
     * Conecta consultas MySQL, resolución semántica y caché de respuestas por versión de catálogo.
     *
     * @param catalog Fachada de consultas del catálogo que conserva filtros, proyecciones y
     *     versiones públicas.
     * @param semanticSearch Cliente que resuelve candidatos completos o declara degradación de toda
     *     la búsqueda a léxica.
     * @param cache Caché de respuestas públicas indexada por argumentos y versión del catálogo.
     */
    @Autowired
    public CatalogController(
            CatalogRepository catalog,
            SemanticSearchClient semanticSearch,
            PublicCatalogCache cache) {
        this.catalog = catalog;
        this.semanticSearch = semanticSearch;
        this.cache = cache;
    }

    /**
     * Normaliza filtros y resuelve un único conjunto de candidatos para página, total e índice
     * alfabético; conserva el modo aplicado y su motivo de degradación.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param tag Parámetros tag repetidos; deben cumplirse todas las etiquetas distintas
     *     solicitadas.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param sort name ordena por nombre; updated y downloads priorizan su fecha o contador antes
     *     del desempate de relevancia.
     * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param searchMode Modo lexical o semantic; null o blanco selecciona lexical.
     * @return página de 1–100 aplicaciones con información de búsqueda e índice alfabético al
     *     ordenar por nombre.
     */
    @GetMapping("/apps")
    public AppSearchResponse apps(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") List<String> operatingSystems,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String publisher,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "lexical") String searchMode) {
        status = publicCatalogStatus(status);
        int safePage = Math.max(1, page);
        int safePageSize = Math.clamp(pageSize, 1, 100);
        List<String> systems = normalizedOperatingSystems(operatingSystems);
        List<String> tagList = parseRepeated(tag);
        List<String> publisherList = optionalPublisher(publisher);
        String normalizedStatus = status;
        CatalogQuery filters = new CatalogQuery(
                query, normalizedStatus, systems, architecture, tagList, publisherList);
        return cache.get(
                "apps",
                catalog::cacheVersion,
                List.of(
                        String.valueOf(query), normalizedStatus, systems, String.valueOf(architecture),
                        tagList, publisherList, sort, safePage, safePageSize, searchMode),
                () -> {
                    SemanticCandidateSet candidates = semanticSearch.resolve(
                            CatalogSearchMode.parse(searchMode), query);
                    return new AppSearchResponse(
                            catalog.search(filters, sort, safePage, safePageSize, candidates),
                            safePage,
                            safePageSize,
                            catalog.count(filters, candidates),
                            "name".equals(sort)
                                    ? catalog.alphabet(filters, safePageSize, candidates)
                                    : List.of(),
                            candidates.requestedMode().wireValue(),
                            candidates.appliedMode().wireValue(),
                            candidates.modelVersion(),
                            candidates.indexVersion(),
                            candidates.degradedReason());
                });
    }

    /**
     * Consulta contadores públicos y última ejecución con la versión de caché del catálogo.
     *
     * @return estadísticas agregadas y fecha de generación.
     */
    @GetMapping("/apps/stats")
    public CatalogStatsResponse stats() {
        return cache.get("stats", catalog::cacheVersion, List.of(), catalog::stats);
    }

    /**
     * Aplica los mismos filtros y candidatos que la búsqueda de aplicaciones antes de contar
     * etiquetas y editores.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param tag Parámetros tag repetidos; deben cumplirse todas las etiquetas distintas
     *     solicitadas.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param searchMode Modo lexical o semantic; null o blanco selecciona lexical.
     * @return facetas coherentes con el modo aplicado y su eventual degradación.
     */
    @GetMapping("/apps/facets")
    public CatalogFacetsResponse facets(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") List<String> operatingSystems,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String publisher,
            @RequestParam(defaultValue = "lexical") String searchMode) {
        status = publicCatalogStatus(status);
        List<String> systems = normalizedOperatingSystems(operatingSystems);
        List<String> tagList = parseRepeated(tag);
        List<String> publisherList = optionalPublisher(publisher);
        String normalizedStatus = status;
        CatalogQuery filters = new CatalogQuery(
                query, normalizedStatus, systems, architecture, tagList, publisherList);
        return cache.get(
                "facets",
                catalog::cacheVersion,
                List.of(
                        String.valueOf(query), normalizedStatus, systems, String.valueOf(architecture),
                        tagList, publisherList, searchMode),
                () -> {
                    SemanticCandidateSet candidates = semanticSearch.resolve(
                            CatalogSearchMode.parse(searchMode), query);
                    CatalogFacetsResponse facets = catalog.facets(filters, candidates);
                    return new CatalogFacetsResponse(
                            facets.tags(),
                            facets.publishers(),
                            candidates.requestedMode().wireValue(),
                            candidates.appliedMode().wireValue(),
                            candidates.modelVersion(),
                            candidates.indexVersion(),
                            candidates.degradedReason());
                });
    }

    /**
     * Cachea el detalle de una aplicación activa usando su identificador y la versión de catálogo.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @return detalle público con opciones exactas sin URLs resueltas.
     */
    @GetMapping("/apps/{appId}")
    public AppDetails details(@PathVariable String appId) {
        return cache.get("details", catalog::cacheVersion, List.of(appId), () -> catalog.details(appId));
    }

    /**
     * Normaliza all, available, review y missing; el filtro administrativo unresolved no forma
     * parte del contrato público.
     *
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @return estado público validado.
     * @throws es.ubu.batchdownloader.common.BadRequestException si se solicita cualquier otro
     *     estado.
     */
    private static String publicCatalogStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? "all"
                : status.trim().toLowerCase(Locale.ROOT);
        if (!PUBLIC_CATALOG_STATUSES.contains(normalized)) {
            throw new BadRequestException(
                    "invalid_catalog_status",
                    "El estado de catálogo indicado no es válido.");
        }
        return normalized;
    }

    /**
     * Recorta, convierte a minúsculas y deduplica plataformas; seleccionar las tres equivale a
     * omitir el filtro.
     *
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @return plataformas válidas o lista vacía para todas.
     * @throws es.ubu.batchdownloader.common.BadRequestException si una lista explícita queda vacía
     *     o contiene una plataforma desconocida.
     */
    private List<String> normalizedOperatingSystems(List<String> operatingSystems) {
        if (operatingSystems == null || operatingSystems.isEmpty()) {
            return List.of();
        }
        List<String> values = operatingSystems.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (values.isEmpty() || !OPERATING_SYSTEMS.containsAll(values)) {
            throw new BadRequestException("invalid_operating_system", "El sistema operativo indicado no es válido.");
        }
        // Seleccionar todas las plataformas equivale a omitir el filtro y conserva
        // el significado de los estados heredados "Todas" y "Sin instalador".
        return values.size() == OPERATING_SYSTEMS.size() ? List.of() : values;
    }

    /**
     * Convierte el editor singular en una lista de cero o un elemento sin dividir nombres
     * compuestos.
     *
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @return editor recortado o lista vacía si falta.
     */
    private List<String> optionalPublisher(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return List.of();
        }
        return List.of(publisher.trim());
    }

    /**
     * Retira valores nulos, blancos y repetidos de parámetros HTTP, conservando su primera
     * aparición tras recortar.
     *
     * @param repeated Valores repetidos del mismo parámetro HTTP antes de retirar nulos, blancos y
     *     duplicados.
     * @return valores explícitos únicos.
     */
    private List<String> parseRepeated(List<String> repeated) {
        if (repeated == null || repeated.isEmpty()) {
            return List.of();
        }
        return repeated.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
