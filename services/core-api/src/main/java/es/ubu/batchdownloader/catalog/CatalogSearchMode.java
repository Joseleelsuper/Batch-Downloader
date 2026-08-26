package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.Locale;

/**
 * Enumera los valores admitidos por {@code CatalogSearchMode}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Estado {@code wireValue} mantenido por {@code CatalogSearchMode}.
     */
    private final String wireValue;

    /**
     * Inicializa una instancia de {@code CatalogSearchMode}.
     *
     * @param wireValue Valor de {@code wireValue} utilizado por la operación.
     */
    CatalogSearchMode(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Ejecuta la operación {@code wireValue}.
     *
     * @return Resultado producido por {@code wireValue}.
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Analiza el contenido recibido mediante {@code parse}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code parse}.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
