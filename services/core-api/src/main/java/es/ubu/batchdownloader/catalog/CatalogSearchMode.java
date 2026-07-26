package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.Locale;

public enum CatalogSearchMode {
    LEXICAL("lexical"),
    SEMANTIC("semantic");

    private final String wireValue;

    CatalogSearchMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

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
