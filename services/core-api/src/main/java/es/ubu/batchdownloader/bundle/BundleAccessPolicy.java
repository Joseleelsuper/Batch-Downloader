package es.ubu.batchdownloader.bundle;

import java.util.UUID;

/** Decide el acceso a un bundle sin ejecutar consultas ni depender de la capa web. */
final class BundleAccessPolicy {
    private BundleAccessPolicy() {}

    /** Permite recursos públicos/oficiales, administradores o al propietario autenticado. */
    static boolean isVisible(
            String visibility,
            UUID ownerId,
            UUID viewerId,
            boolean administrator) {
        if ("public".equals(visibility) || "official".equals(visibility) || administrator) {
            return true;
        }
        return viewerId != null && ownerId != null && ownerId.equals(viewerId);
    }
}
