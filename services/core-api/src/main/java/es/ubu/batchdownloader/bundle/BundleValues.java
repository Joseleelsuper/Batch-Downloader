package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.common.UuidBytes;
import java.util.Locale;
import java.util.UUID;

/**
 * Comparte normalización de tipo, visibilidad, slug e identidades del flujo administrativo de
 * bundles.
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
final class BundleValues {
    static final int MAX_BUNDLE_APPS = 100;

    /**
     * Impide instancias del conjunto de normalizaciones estáticas.
     */
    private BundleValues() {}

    /**
     * Conserva community o user y utiliza official para cualquier otro valor.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @return tipo persistible del flujo administrativo.
     */
    static String normalizedType(String type) {
        return "community".equals(type) || "user".equals(type) ? type : "official";
    }

    /**
     * Conserva private o public y utiliza official para cualquier otro valor.
     *
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @return visibilidad persistible del flujo administrativo.
     */
    static String normalizedVisibility(String visibility) {
        return "private".equals(visibility) || "public".equals(visibility)
                ? visibility
                : "official";
    }

    /**
     * Reduce el texto a letras ASCII minúsculas, dígitos y grupos de guiones; usa bundle-UUID
     * cuando no queda contenido.
     *
     * @param value Texto de entrada que se normaliza según el contrato de la operación.
     * @return slug normalizado que aún debe comprobarse contra colisiones.
     */
    static String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    /**
     * Interpreta un identificador textual como UUID binario para permitir búsquedas alternativas
     * por slug.
     *
     * @param publicId UUID textual o slug del bundle solicitado.
     * @return dieciséis bytes o null si falta o no es un UUID.
     */
    static byte[] uuidBytesOrNull(String publicId) {
        try {
            return publicId == null || publicId.isBlank()
                    ? null
                    : UuidBytes.fromUuid(UUID.fromString(publicId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Representa la ausencia de un filtro con null, sin recortar los textos presentes.
     *
     * @param value Texto de entrada que se normaliza según el contrato de la operación.
     * @return valor original no blanco o null.
     */
    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
