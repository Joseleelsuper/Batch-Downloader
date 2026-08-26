package es.ubu.batchdownloader.downloads.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Define el contrato de {@code CatalogSourceLookup}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface CatalogSourceLookup {
    /**
     * Metadatos públicos suficientes para ofrecer una descarga manual segura.
     *
     * @param appId Identificador de la aplicación.
     * @param appName Nombre público de la aplicación.
     * @param officialPageUrl Página oficial publicada.
     */
    record ManualSource(UUID appId, String appName, String officialPageUrl) {}

    /**
     * Representa los datos inmutables de {@code VerifiedSource}.
     *
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param officialPageUrl Valor de {@code officialPageUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record VerifiedSource(
            UUID appId,
            UUID sourceRef,
            String operatingSystem,
            String architecture,
            String appName,
            String officialPageUrl) {
        /**
         * Inicializa una instancia de {@code VerifiedSource}.
         *
         * @param appId Identificador de {@code app} utilizado por la operación.
         * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
         * @param operatingSystem Valor de {@code operatingSystem} utilizado por la operación.
         * @param architecture Valor de {@code architecture} utilizado por la operación.
         */
        public VerifiedSource(UUID appId, UUID sourceRef, String operatingSystem, String architecture) {
            this(appId, sourceRef, operatingSystem, architecture, appId.toString(), null);
        }
    }

    /**
     * Busca el resultado solicitado mediante {@code findVerifiedSources}.
     *
     * @param appIds Colección de identificadores de {@code app}.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @return Mapa con los datos producidos por la operación.
     */
    Map<UUID, VerifiedSource> findVerifiedSources(Collection<UUID> appIds, List<String> operatingSystems);

    /**
     * Recupera las aplicaciones activas que pueden representarse mediante su página oficial.
     *
     * @param appIds Identificadores solicitados.
     * @return Metadatos públicos indexados por aplicación.
     */
    Map<UUID, ManualSource> findManualSources(Collection<UUID> appIds);

    /**
     * Busca el resultado solicitado mediante {@code findVerifiedSource}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @return Resultado producido por {@code findVerifiedSource}.
     */
    default Optional<VerifiedSource> findVerifiedSource(UUID appId, List<String> operatingSystems) {
        return Optional.ofNullable(findVerifiedSources(List.of(appId), operatingSystems).get(appId));
    }

    /**
     * Busca una fuente concreta y comprueba que siga siendo descargable para la aplicación.
     *
     * @param appId Identificador de la aplicación propietaria de la fuente.
     * @param sourceRef Identificador de la fuente resuelta elegida por el usuario.
     * @param operatingSystems Sistemas operativos admitidos por la solicitud.
     * @return La fuente verificada, o vacío si no pertenece a la aplicación o ya no es válida.
     */
    default Optional<VerifiedSource> findVerifiedSource(
            UUID appId, UUID sourceRef, List<String> operatingSystems) {
        return findVerifiedSource(appId, operatingSystems)
                .filter(source -> source.sourceRef().equals(sourceRef));
    }
}
