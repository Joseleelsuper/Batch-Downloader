package es.ubu.batchdownloader.catalog;

import java.util.Objects;

public record SemanticCandidateSet(
        CatalogSearchMode requestedMode,
        CatalogSearchMode appliedMode,
        String candidatesJson,
        String modelVersion,
        String indexVersion,
        String degradedReason) {

    public SemanticCandidateSet {
        Objects.requireNonNull(requestedMode);
        Objects.requireNonNull(appliedMode);
        candidatesJson = candidatesJson == null ? "[]" : candidatesJson;
    }

    public static SemanticCandidateSet lexical(CatalogSearchMode requestedMode, String degradedReason) {
        return new SemanticCandidateSet(
                requestedMode,
                CatalogSearchMode.LEXICAL,
                "[]",
                null,
                null,
                degradedReason);
    }

    public static SemanticCandidateSet lexical() {
        return lexical(CatalogSearchMode.LEXICAL, null);
    }

    public boolean semantic() {
        return appliedMode == CatalogSearchMode.SEMANTIC;
    }
}
