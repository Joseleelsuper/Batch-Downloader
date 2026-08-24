package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.common.UuidBytes;
import java.util.Locale;
import java.util.UUID;

/** Normalización e identificadores compartidos por las lecturas y escrituras de bundles. */
final class BundleValues {
    static final int MAX_BUNDLE_APPS = 100;

    private BundleValues() {}

    static String normalizedType(String type) {
        return "community".equals(type) || "user".equals(type) ? type : "official";
    }

    static String normalizedVisibility(String visibility) {
        return "private".equals(visibility) || "public".equals(visibility)
                ? visibility
                : "official";
    }

    static String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "bundle-" + UUID.randomUUID() : slug;
    }

    static byte[] uuidBytesOrNull(String publicId) {
        try {
            return publicId == null || publicId.isBlank()
                    ? null
                    : UuidBytes.fromUuid(UUID.fromString(publicId));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
