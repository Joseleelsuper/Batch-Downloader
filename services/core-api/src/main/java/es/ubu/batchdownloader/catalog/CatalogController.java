package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Versioned public read API. ZIP creation is intentionally handled by download jobs. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private static final Set<String> OPERATING_SYSTEMS = Set.of("windows", "linux", "macos");
    private final CatalogRepository catalog;
    private final SemanticSearchClient semanticSearch;

    @Autowired
    public CatalogController(CatalogRepository catalog, SemanticSearchClient semanticSearch) {
        this.catalog = catalog;
        this.semanticSearch = semanticSearch;
    }

    CatalogController(CatalogRepository catalog) {
        this(catalog, SemanticSearchClient.disabled());
    }

    @GetMapping("/apps")
    public AppSearchResponse apps(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") List<String> operatingSystems,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) List<String> publisher,
            @RequestParam(required = false) Integer tagMatchMin,
            @RequestParam(defaultValue = "all") String tagMode,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "lexical") String searchMode) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> systems = normalizedOperatingSystems(operatingSystems);
        List<String> tagList = parseRepeatedAndCsv(tag, tags);
        List<String> publisherList = parseRepeated(publisher);
        HybridCandidateSet candidates = semanticSearch.resolve(
                CatalogSearchMode.parse(searchMode),
                query);
        return new AppSearchResponse(
                catalog.search(
                        query,
                        status,
                        systems,
                        architecture,
                        tagList,
                        publisherList,
                        tagMatchMin,
                        tagMode,
                        sort,
                        safePage,
                        safePageSize,
                        candidates),
                safePage,
                safePageSize,
                catalog.count(
                        query,
                        status,
                        systems,
                        architecture,
                        tagList,
                        publisherList,
                        tagMatchMin,
                        tagMode,
                        candidates),
                candidates.requestedMode().wireValue(),
                candidates.appliedMode().wireValue(),
                candidates.modelVersion(),
                candidates.indexVersion(),
                candidates.degradedReason());
    }

    AppSearchResponse apps(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tag,
            String tags,
            List<String> publisher,
            Integer tagMatchMin,
            String tagMode,
            String sort,
            int page,
            int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> systems = normalizedOperatingSystems(operatingSystems);
        List<String> tagList = parseRepeatedAndCsv(tag, tags);
        List<String> publisherList = parseRepeated(publisher);
        return new AppSearchResponse(
                catalog.search(
                        query,
                        status,
                        systems,
                        architecture,
                        tagList,
                        publisherList,
                        tagMatchMin,
                        tagMode,
                        sort,
                        safePage,
                        safePageSize),
                safePage,
                safePageSize,
                catalog.count(
                        query,
                        status,
                        systems,
                        architecture,
                        tagList,
                        publisherList,
                        tagMatchMin,
                        tagMode));
    }

    @GetMapping("/apps/stats")
    public CatalogStatsResponse stats() {
        return catalog.stats();
    }

    @GetMapping("/apps/facets")
    public CatalogFacetsResponse facets(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") List<String> operatingSystems,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) List<String> publisher,
            @RequestParam(required = false) Integer tagMatchMin,
            @RequestParam(defaultValue = "all") String tagMode,
            @RequestParam(defaultValue = "lexical") String searchMode) {
        HybridCandidateSet candidates = semanticSearch.resolve(
                CatalogSearchMode.parse(searchMode),
                query);
        CatalogFacetsResponse facets = catalog.facets(
                query,
                status,
                normalizedOperatingSystems(operatingSystems),
                architecture,
                parseRepeatedAndCsv(tag, tags),
                parseRepeated(publisher),
                tagMatchMin,
                tagMode,
                candidates);
        return new CatalogFacetsResponse(
                facets.tags(),
                facets.publishers(),
                candidates.requestedMode().wireValue(),
                candidates.appliedMode().wireValue(),
                candidates.modelVersion(),
                candidates.indexVersion(),
                candidates.degradedReason());
    }

    CatalogFacetsResponse facets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tag,
            String tags,
            List<String> publisher,
            Integer tagMatchMin,
            String tagMode) {
        return catalog.facets(
                query,
                status,
                normalizedOperatingSystems(operatingSystems),
                architecture,
                parseRepeatedAndCsv(tag, tags),
                parseRepeated(publisher),
                tagMatchMin,
                tagMode);
    }

    @GetMapping("/apps/{appId}")
    public AppDetails details(@PathVariable String appId) {
        return catalog.details(appId);
    }

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
        // All platforms is equivalent to an omitted filter and keeps the legacy
        // "Todas" and "Sin instalador" states meaningful.
        return values.size() == OPERATING_SYSTEMS.size() ? List.of() : values;
    }

    private List<String> parseRepeatedAndCsv(List<String> repeated, String csv) {
        List<String> values = new ArrayList<>(parseRepeated(repeated));
        if (csv != null && !csv.isBlank()) {
            for (String value : csv.split(",")) {
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

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
