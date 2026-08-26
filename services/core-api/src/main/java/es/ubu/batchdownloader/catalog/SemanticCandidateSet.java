package es.ubu.batchdownloader.catalog;

import java.util.Objects;

/**
 * Representa los datos inmutables de {@code SemanticCandidateSet}.
 *
 * @param requestedMode Valor de {@code requestedMode} incluido en el record.
 * @param appliedMode Valor de {@code appliedMode} incluido en el record.
 * @param candidatesJson Valor de {@code candidatesJson} incluido en el record.
 * @param modelVersion Valor de {@code modelVersion} incluido en el record.
 * @param indexVersion Valor de {@code indexVersion} incluido en el record.
 * @param degradedReason Valor de {@code degradedReason} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record SemanticCandidateSet(
        CatalogSearchMode requestedMode,
        CatalogSearchMode appliedMode,
        String candidatesJson,
        String modelVersion,
        String indexVersion,
        String degradedReason) {

    /**
     * Inicializa una instancia de {@code SemanticCandidateSet}.
     *
     * @param requestedMode Valor de {@code requestedMode} utilizado por la operación.
     * @param appliedMode Valor de {@code appliedMode} utilizado por la operación.
     * @param candidatesJson Valor de {@code candidatesJson} utilizado por la operación.
     * @param modelVersion Valor de {@code modelVersion} utilizado por la operación.
     * @param indexVersion Valor de {@code indexVersion} utilizado por la operación.
     * @param degradedReason Valor de {@code degradedReason} utilizado por la operación.
     */
    public SemanticCandidateSet {
        Objects.requireNonNull(requestedMode);
        Objects.requireNonNull(appliedMode);
        candidatesJson = candidatesJson == null ? "[]" : candidatesJson;
    }

    /**
     * Ejecuta la operación {@code lexical}.
     *
     * @param requestedMode Valor de {@code requestedMode} utilizado por la operación.
     * @param degradedReason Valor de {@code degradedReason} utilizado por la operación.
     * @return Resultado producido por {@code lexical}.
     */
    public static SemanticCandidateSet lexical(CatalogSearchMode requestedMode, String degradedReason) {
        return new SemanticCandidateSet(
                requestedMode,
                CatalogSearchMode.LEXICAL,
                "[]",
                null,
                null,
                degradedReason);
    }

    /**
     * Ejecuta la operación {@code lexical}.
     *
     * @return Resultado producido por {@code lexical}.
     */
    public static SemanticCandidateSet lexical() {
        return lexical(CatalogSearchMode.LEXICAL, null);
    }

    /**
     * Ejecuta la operación {@code semantic}.
     *
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean semantic() {
        return appliedMode == CatalogSearchMode.SEMANTIC;
    }
}
