package es.ubu.batchdownloader.downloads.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ofrece a la admisión fuentes verificadas y alternativas manuales del catálogo sin exponer su
 * persistencia.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.DownloadSelection
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface CatalogSourceLookup {
    /**
     * Ofrece una selección Linux de compatibilidad mediante la consulta general.
     * El método por defecto no aplica destino ni fuente exacta; el adaptador de producción debe
     * sobrescribirlo para respetar ambos.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @param target Gestor Linux seleccionado o contexto validado del destino según la firma.
     * @param exactSource Fuente resuelta obligatoria para la primera aplicación; null permite
     *     selección automática.
     * @return una fuente verificada por aplicación disponible; se omiten las que no tienen
     *     candidato.
     */
    default Map<UUID, VerifiedSource> findLinuxSources(Collection<UUID> appIds,
            es.ubu.batchdownloader.downloads.application.LinuxTarget target, UUID exactSource) {
        return findVerifiedSources(appIds, List.of("linux"));
    }

    /**
     * Conserva la selección sin expandirla en la implementación por defecto; los adaptadores con
     * catálogo de dependencias pueden ampliarla.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @return copia de los UUID recibidos, en el mismo orden.
     */
    default List<UUID> expandLinuxDependencies(Collection<UUID> appIds) {
        return List.copyOf(appIds);
    }
    /**
     * Conserva nombre y página oficial para incluir instrucciones manuales cuando no existe un
     * instalador utilizable.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    record ManualSource(UUID appId, String appName, String officialPageUrl) {}

    /**
     * Identifica el instalador concreto admitido y los metadatos necesarios para resolverlo y
     * explicar su capacidad de instalación.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     * @param operatingSystem Plataforma de la fuente elegida: windows, linux o macos.
     * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     * @param installationSupport automatic, manual o unavailable según la capacidad de instalación
     *     del destino.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    record VerifiedSource(
            UUID appId,
            UUID sourceRef,
            String operatingSystem,
            String architecture,
            String appName,
            String officialPageUrl,
            String installationSupport) {
        /**
         * Construye una selección verificada sin una clasificación explícita de instalación Linux.
         *
         * @param appId UUID público de la aplicación del catálogo.
         * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa
         *     una alternativa manual.
         * @param operatingSystem Plataforma de la fuente elegida: windows, linux o macos.
         * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
         * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
         * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de
         *     instalador resuelta.
         */
        public VerifiedSource(UUID appId, UUID sourceRef, String operatingSystem, String architecture,
                String appName, String officialPageUrl) {
            this(appId, sourceRef, operatingSystem, architecture, appName, officialPageUrl, null);
        }
        /**
         * Construye una selección mínima cuyo nombre provisional es el UUID; no aporta página
         * oficial ni capacidad de instalación.
         *
         * @param appId UUID público de la aplicación del catálogo.
         * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa
         *     una alternativa manual.
         * @param operatingSystem Plataforma de la fuente elegida: windows, linux o macos.
         * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
         */
        public VerifiedSource(UUID appId, UUID sourceRef, String operatingSystem, String architecture) {
            this(appId, sourceRef, operatingSystem, architecture, appId.toString(), null);
        }
    }

    /**
     * Selecciona como máximo un instalador verificado por aplicación dentro de las plataformas
     * solicitadas.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return fuentes indexadas por UUID de aplicación; las aplicaciones sin candidato no aparecen.
     */
    Map<UUID, VerifiedSource> findVerifiedSources(Collection<UUID> appIds, List<String> operatingSystems);

    /**
     * Recupera aplicaciones que ofrecen una página oficial utilizable como alternativa manual.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @return páginas oficiales indexadas por UUID, sin incluir aplicaciones sin alternativa.
     */
    Map<UUID, ManualSource> findManualSources(Collection<UUID> appIds);

    /**
     * Consulta la selección general para una sola aplicación.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return instalador preferido, o vacío si no hay una fuente verificada.
     */
    default Optional<VerifiedSource> findVerifiedSource(UUID appId, List<String> operatingSystems) {
        return Optional.ofNullable(findVerifiedSources(List.of(appId), operatingSystems).get(appId));
    }

    /**
     * Acepta la selección general solo si coincide con la fuente exacta solicitada.
     * Los adaptadores que permiten elegir otras fuentes verificadas deben sobrescribir esta
     * consulta; nunca se devuelve una fuente distinta.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID concreto que debe coincidir con la fuente seleccionada; null no permite
     *     coincidencia.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @return fuente coincidente o vacío si la selección general es distinta.
     */
    default Optional<VerifiedSource> findVerifiedSource(
            UUID appId, UUID sourceRef, List<String> operatingSystems) {
        return findVerifiedSource(appId, operatingSystems)
                .filter(source -> source.sourceRef().equals(sourceRef));
    }
}
