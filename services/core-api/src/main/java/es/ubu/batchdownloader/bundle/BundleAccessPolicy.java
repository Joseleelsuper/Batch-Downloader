package es.ubu.batchdownloader.bundle;

import java.util.UUID;

/**
 * Comparte la regla de visibilidad entre consulta de detalle y selección para descarga, ocultando
 * bundles privados ajenos.
 *
 * @see es.ubu.batchdownloader.bundle.BundleReadRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
final class BundleAccessPolicy {
    /**
     * Impide instancias de la política estática de visibilidad.
     */
    private BundleAccessPolicy() {}

    /**
     * Permite visibilidad pública u oficial, administración o coincidencia de propietario con el
     * visitante autenticado.
     *
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param ownerId UUID canónico de la cuenta propietaria; null para bundles sin propietario
     *     asignado.
     * @param viewerId UUID de quien consulta, o null para visitantes anónimos.
     * @param administrator Permite al administrador consultar bundles de cualquier visibilidad.
     * @return true si el bundle puede consultarse con esas identidades.
     */
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
