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
 * Expone las operaciones HTTP gestionadas por {@code CatalogController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    /**
     * Constante que define {@code OPERATING_SYSTEMS}.
     */
    private static final Set<String> OPERATING_SYSTEMS = Set.of("windows", "linux", "macos");
    /**
     * Constante que define {@code PUBLIC_CATALOG_STATUSES}.
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
     * Inicializa una instancia de {@code CatalogController}.
     *
     * @param catalog Acceso al catálogo utilizado por la operación.
     * @param semanticSearch Valor de {@code semanticSearch} utilizado por la operación.
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
     * Ejecuta la operación {@code apps}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tag Valor de {@code tag} utilizado por la operación.
     * @param publisher Valor de {@code publisher} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @param searchMode Valor de {@code searchMode} utilizado por la operación.
     * @return Resultado producido por {@code apps}.
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
                            catalog.search(
                                    query, normalizedStatus, systems, architecture, tagList,
                                    publisherList, sort, safePage, safePageSize, candidates),
                            safePage,
                            safePageSize,
                            catalog.count(
                                    query, normalizedStatus, systems, architecture, tagList,
                                    publisherList, candidates),
                            "name".equals(sort)
                                    ? catalog.alphabet(
                                            query, normalizedStatus, systems, architecture, tagList,
                                            publisherList, safePageSize, candidates)
                                    : List.of(),
                            candidates.requestedMode().wireValue(),
                            candidates.appliedMode().wireValue(),
                            candidates.modelVersion(),
                            candidates.indexVersion(),
                            candidates.degradedReason());
                });
    }

    /**
     * Ejecuta la operación {@code stats}.
     *
     * @return Resultado producido por {@code stats}.
     */
    @GetMapping("/apps/stats")
    public CatalogStatsResponse stats() {
        return cache.get("stats", catalog::cacheVersion, List.of(), catalog::stats);
    }

    /**
     * Ejecuta la operación {@code facets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tag Valor de {@code tag} utilizado por la operación.
     * @param publisher Valor de {@code publisher} utilizado por la operación.
     * @param searchMode Valor de {@code searchMode} utilizado por la operación.
     * @return Resultado producido por {@code facets}.
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
        return cache.get(
                "facets",
                catalog::cacheVersion,
                List.of(
                        String.valueOf(query), normalizedStatus, systems, String.valueOf(architecture),
                        tagList, publisherList, searchMode),
                () -> {
                    SemanticCandidateSet candidates = semanticSearch.resolve(
                            CatalogSearchMode.parse(searchMode), query);
                    CatalogFacetsResponse facets = catalog.facets(
                            query, normalizedStatus, systems, architecture, tagList,
                            publisherList, candidates);
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
     * Ejecuta la operación {@code details}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code details}.
     */
    @GetMapping("/apps/{appId}")
    public AppDetails details(@PathVariable String appId) {
        return cache.get("details", catalog::cacheVersion, List.of(appId), () -> catalog.details(appId));
    }

    /**
     * Ejecuta la operación {@code publicCatalogStatus}.
     *
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @return Resultado producido por {@code publicCatalogStatus}.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
     * Normaliza el valor recibido mediante {@code normalizedOperatingSystems}.
     *
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
     * Convierte el editor público opcional en la colección esperada por el repositorio.
     *
     * @param publisher Editor recibido en la consulta pública.
     * @return Una colección vacía o con el único editor seleccionado.
     */
    private List<String> optionalPublisher(String publisher) {
        if (publisher == null || publisher.isBlank()) {
            return List.of();
        }
        return List.of(publisher.trim());
    }

    /**
     * Analiza el contenido recibido mediante {@code parseRepeated}.
     *
     * @param repeated Valor de {@code repeated} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
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
