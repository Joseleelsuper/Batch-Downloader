package es.ubu.batchdownloader.catalog;

import java.util.Objects;

/**
 * Conserva el modo solicitado y el realmente aplicado para que página, recuentos y facetas
 * compartan candidatos o la misma degradación léxica.
 *
 * @param requestedMode Modo de búsqueda solicitado por el cliente.
 * @param appliedMode Modo realmente aplicado a resultados, total y facetas.
 * @param candidatesJson Array JSON de UUID, rango y similitud de los candidatos; [] representa
 *     ausencia.
 * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
 * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda léxica.
 * @param degradedReason Código seguro de degradación a léxica o null si no hubo fallo que
 *     comunicar.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.SemanticSearchClient
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public record SemanticCandidateSet(
        CatalogSearchMode requestedMode,
        CatalogSearchMode appliedMode,
        String candidatesJson,
        String modelVersion,
        String indexVersion,
        String degradedReason) {

    /**
     * Exige ambos modos y representa un cuerpo de candidatos ausente mediante un array JSON vacío.
     *
     * @param requestedMode Modo de búsqueda solicitado por el cliente.
     * @param appliedMode Modo realmente aplicado a resultados, total y facetas.
     * @param candidatesJson Array JSON de UUID, rango y similitud de los candidatos; [] representa
     *     ausencia.
     * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
     * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda
     *     léxica.
     * @param degradedReason Código seguro de degradación a léxica o null si no hubo fallo que
     *     comunicar.
     */
    public SemanticCandidateSet {
        Objects.requireNonNull(requestedMode);
        Objects.requireNonNull(appliedMode);
        candidatesJson = candidatesJson == null ? "[]" : candidatesJson;
    }

    /**
     * Representa una búsqueda aplicada como léxica, conservando la intención original y el
     * diagnóstico de degradación.
     *
     * @param requestedMode Modo de búsqueda solicitado por el cliente.
     * @param degradedReason Código seguro de degradación a léxica o null si no hubo fallo que
     *     comunicar.
     * @return conjunto sin candidatos ni versiones semánticas.
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
     * Representa una búsqueda solicitada y aplicada como léxica sin motivo de degradación.
     *
     * @return conjunto léxico vacío.
     */
    public static SemanticCandidateSet lexical() {
        return lexical(CatalogSearchMode.LEXICAL, null);
    }

    /**
     * Consulta el modo aplicado, sin confundirlo con la intención original del cliente.
     *
     * @return true únicamente si deben utilizarse candidatos semánticos.
     */
    public boolean semantic() {
        return appliedMode == CatalogSearchMode.SEMANTIC;
    }
}
