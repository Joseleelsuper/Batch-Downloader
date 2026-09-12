package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.Locale;

/**
 * Distingue búsqueda léxica de búsqueda por embeddings y conserva sus valores explícitos del
 * contrato HTTP.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.SemanticSearchClient
 * @see es.ubu.batchdownloader.catalog.SemanticCandidateSet
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public enum CatalogSearchMode {
    /**
     * Constante que define {@code LEXICAL}.
     */
    LEXICAL("lexical"),
    /**
     * Constante que define {@code SEMANTIC}.
     */
    SEMANTIC("semantic");

    /**
     * Lexical o semantic.
     */
    private final String wireValue;

    /**
     * Asocia cada modo de búsqueda con el valor que intercambia el cliente HTTP.
     *
     * @param wireValue Nombre lexical o semantic utilizado en el contrato HTTP.
     */
    CatalogSearchMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Expone el nombre estable del modo para respuestas y parámetros HTTP.
     *
     * @return lexical o semantic.
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Recorta y normaliza el modo y utiliza lexical cuando falta.
     *
     * @param value Texto que se normaliza o clasifica según el método.
     * @return modo reconocido.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el texto no corresponde a
     *     lexical ni semantic.
     */
    public static CatalogSearchMode parse(String value) {
        String normalized = value == null || value.isBlank()
                ? LEXICAL.wireValue
                : value.trim().toLowerCase(Locale.ROOT);
        for (CatalogSearchMode mode : values()) {
            if (mode.wireValue.equals(normalized)) {
                return mode;
            }
        }
        throw new BadRequestException(
                "invalid_search_mode",
                "El modo de búsqueda indicado no es válido.");
    }
}
