package es.ubu.batchdownloader.catalog;

import java.util.Objects;

public record HybridCandidateSet(
        CatalogSearchMode requestedMode,
        CatalogSearchMode appliedMode,
        String candidatesJson,
        String modelVersion,
        String indexVersion,
        String degradedReason,
        double semanticWeight) {

    public HybridCandidateSet {
        Objects.requireNonNull(requestedMode);
        Objects.requireNonNull(appliedMode);
        candidatesJson = candidatesJson == null ? "[]" : candidatesJson;
    }

    public static HybridCandidateSet lexical(CatalogSearchMode requestedMode, String degradedReason) {
        return new HybridCandidateSet(
                requestedMode,
                CatalogSearchMode.LEXICAL,
                "[]",
                null,
                null,
                degradedReason,
                1.0);
    }

    public static HybridCandidateSet lexical() {
        return lexical(CatalogSearchMode.LEXICAL, null);
    }

    public boolean hybrid() {
        return appliedMode == CatalogSearchMode.HYBRID;
    }
}
