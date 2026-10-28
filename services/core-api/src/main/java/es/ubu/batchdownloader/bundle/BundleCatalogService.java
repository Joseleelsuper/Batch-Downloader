package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleCatalogRepository.Filters;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleCatalogResponse;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleFacets;
import es.ubu.batchdownloader.catalog.CatalogSearchMode;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import es.ubu.batchdownloader.catalog.SemanticSearchClient;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resuelve candidatos remotos antes de abrir la transacción de lectura del catálogo de bundles. */
@Service
public class BundleCatalogService {
    private final BundleCatalogRepository catalog;
    private final SemanticSearchClient semantic;

    public BundleCatalogService(BundleCatalogRepository catalog, SemanticSearchClient semantic) {
        this.catalog = catalog;
        this.semantic = semantic;
    }

    public BundleCatalogResponse search(Filters filters, String sort, int page, int pageSize, UUID viewer) {
        SemanticCandidateSet candidates = candidates(filters);
        return catalog.search(filters, sort, page, pageSize, viewer, candidates);
    }

    public BundleFacets facets(Filters filters) {
        return catalog.facets(filters, candidates(filters));
    }

    private SemanticCandidateSet candidates(Filters filters) {
        return semantic.resolveBundles(CatalogSearchMode.parse(filters.searchMode()), filters.query());
    }
}
