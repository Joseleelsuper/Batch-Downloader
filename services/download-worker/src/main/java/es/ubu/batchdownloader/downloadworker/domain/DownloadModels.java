package es.ubu.batchdownloader.downloadworker.domain;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agrupa los datos inmutables que conectan resolución, descarga, almacenamiento y manifiesto del
 * worker, manteniendo separada la URI privada de los metadatos entregables.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see DownloadModels.ResolvedDownloadItem
 * @see DownloadModels.DownloadManifest
 * @since 0.1.0
 * @version 0.1.0
 * @category Datos de descarga
 */
public final class DownloadModels {
    /**
     * Impide instanciar el contenedor de datos del pipeline de descarga.
     */
    private DownloadModels() {
    }

    /**
     * Vincula el elemento admitido y su fuente exacta con la URI revalidada y las expectativas de
     * integridad que debe comprobar la descarga.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
     *     candidato automático.
     * @param url URI final revalidada que usa el worker; no se incluye en los manifiestos
     *     entregados.
     * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
     *     manifiesto.
     * @param operatingSystem Plataforma declarada para la fuente concreta.
     * @param architecture Arquitectura declarada para la fuente concreta.
     * @param expectedSizeBytes Tamaño esperado en bytes; null cuando la inspección no lo conoce.
     * @param expectedSha256 SHA-256 esperado para verificar integridad; null si no se conoce.
     * @param expectedMime Tipo MIME esperado de la fuente, cuando la resolución lo proporciona.
     * @param installation Metadatos declarativos de instalación; null para productores que no los
     *     proporcionan.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record ResolvedDownloadItem(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            URI url,
            String filename,
            String operatingSystem,
            String architecture,
            Long expectedSizeBytes,
            String expectedSha256,
            String expectedMime,
            InstallationMetadata installation) {
        /**
         * Construye una resolución compatible con productores que todavía no aportan metadatos de
         * instalación.
         *
         * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
         * @param appId UUID de la aplicación seleccionada en el catálogo.
         * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
         *     candidato automático.
         * @param url URI final revalidada que usa el worker; no se incluye en los manifiestos
         *     entregados.
         * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
         *     manifiesto.
         * @param operatingSystem Plataforma declarada para la fuente concreta.
         * @param architecture Arquitectura declarada para la fuente concreta.
         * @param expectedSizeBytes Tamaño esperado en bytes; null cuando la inspección no lo
         *     conoce.
         * @param expectedSha256 SHA-256 esperado para verificar integridad; null si no se conoce.
         * @param expectedMime Tipo MIME esperado de la fuente, cuando la resolución lo proporciona.
         */
        public ResolvedDownloadItem(UUID itemId, UUID appId, UUID sourceRef, URI url,
                String filename, String operatingSystem, String architecture, Long expectedSizeBytes,
                String expectedSha256, String expectedMime) {
            this(itemId, appId, sourceRef, url, filename, operatingSystem, architecture,
                    expectedSizeBytes, expectedSha256, expectedMime, null);
        }
    }

    /**
     * Transporta la receta y las propiedades necesarias para preparar un instalador Linux sin
     * incluir la URI final privada del proveedor.
     *
     * @param appName Nombre de la aplicación que se muestra en el manifiesto o las instrucciones
     *     manuales.
     * @param version Versión del programa correspondiente al instalador, cuando se conoce.
     * @param extension Formato del instalador sin incluir una dirección de descarga.
     * @param operatingSystem Plataforma declarada para la fuente concreta.
     * @param architecture Arquitectura declarada para la fuente concreta.
     * @param profile Receta Linux declarativa aprobada y sus condiciones; puede faltar para
     *     formatos sin receta.
     * @param signatureBase64 Firma separada del instalador codificada en Base64, cuando la fuente
     *     la proporciona.
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record InstallationMetadata(String appName, String version, String extension,
            String operatingSystem, String architecture, Map<String, Object> profile,
            String signatureBase64) {}

    /**
     * Conserva la ubicación local, identidad exacta e integridad calculada de un instalador
     * descargado para almacenarlo y añadirlo al ZIP.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
     *     candidato automático.
     * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
     *     manifiesto.
     * @param path Ruta local del archivo completo, cuya limpieza corresponde al ciclo de vida del
     *     trabajo.
     * @param sizeBytes Longitud comprobada del archivo, en bytes.
     * @param sha256 SHA-256 calculado del contenido descargado, cuando existe un archivo.
     * @param objectKey Clave del artefacto en almacenamiento de objetos; no contiene la URL final
     *     del proveedor.
     * @param installation Metadatos declarativos de instalación; null para productores que no los
     *     proporcionan.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record DownloadedArtifact(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String filename,
            Path path,
            long sizeBytes,
            String sha256,
            String objectKey,
            InstallationMetadata installation) {
        /**
         * Construye un artefacto descargado sin metadatos de instalación para consumidores del
         * contrato abreviado.
         *
         * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
         * @param appId UUID de la aplicación seleccionada en el catálogo.
         * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
         *     candidato automático.
         * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
         *     manifiesto.
         * @param path Ruta local del archivo completo.
         * @param sizeBytes Longitud comprobada del archivo, en bytes.
         * @param sha256 SHA-256 calculado del contenido descargado, cuando existe un archivo.
         * @param objectKey Clave del artefacto en almacenamiento de objetos; no contiene la URL
         *     final del proveedor.
         */
        public DownloadedArtifact(UUID itemId, UUID appId, UUID sourceRef, String filename,
                Path path, long sizeBytes, String sha256, String objectKey) {
            this(itemId, appId, sourceRef, filename, path, sizeBytes, sha256, objectKey, null);
        }
    }

    /**
     * Identifica el elemento y la fuente que fallaron para conservar el fallo individual sin
     * descartar el resto del trabajo.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
     *     candidato automático.
     * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
     *     manifiesto.
     * @param errorCode Código seguro de fallo que se conserva en el resultado parcial.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record FailedDownload(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String filename,
            String errorCode) {
    }

    /**
     * Aporta nombre y página oficial de un elemento para describirlo y generar una alternativa
     * manual sin exponer URLs resueltas.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param appName Nombre de la aplicación que se muestra en el manifiesto o las instrucciones
     *     manuales.
     * @param officialPageUrl Página oficial utilizada como alternativa manual cuando no puede
     *     entregarse un instalador.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record DownloadItemMetadata(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {
    }

    /**
     * Relaciona un archivo local complementario con su ubicación prevista dentro del archivo de
     * descarga.
     *
     * @param path Nombre relativo de destino dentro del ZIP.
     * @param source Ruta del archivo local que se copiará a la entrada del archivo.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record ArchiveEntry(
            String path,
            Path source) {
    }

    /**
     * Describe el resultado observable de un elemento: archivo e integridad, fallo o alternativa
     * manual, junto a compatibilidad e instalación Linux.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
     *     candidato automático.
     * @param appName Nombre de la aplicación que se muestra en el manifiesto o las instrucciones
     *     manuales.
     * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
     *     manifiesto.
     * @param status Estado del resultado representado, distinto del estado de validación de la
     *     fuente.
     * @param sizeBytes Número de bytes del archivo descargado; null en un elemento de manifiesto
     *     sin archivo.
     * @param sha256 SHA-256 calculado del contenido descargado, cuando existe un archivo.
     * @param archivePath Nombre relativo del archivo dentro del ZIP para localizar el instalador
     *     desde el manifiesto.
     * @param objectKey Clave del artefacto en almacenamiento de objetos; no contiene la URL final
     *     del proveedor.
     * @param error Diagnóstico seguro del elemento fallido; null cuando no hubo fallo.
     * @param manualShortcut Ruta de la entrada que contiene instrucciones o acceso manual, cuando
     *     se generó.
     * @param operatingSystem Plataforma declarada para la fuente concreta.
     * @param architecture Arquitectura declarada para la fuente concreta.
     * @param version Versión del programa correspondiente al instalador, cuando se conoce.
     * @param installationSupport Clasificación que indica si el runtime Linux puede instalar el
     *     formato y receta recibidos.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record ManifestItem(
            UUID itemId,
            UUID appId,
            UUID sourceRef,
            String appName,
            String filename,
            String status,
            Long sizeBytes,
            String sha256,
            String archivePath,
            String objectKey,
            String error,
            String manualShortcut,
            String operatingSystem,
            String architecture,
            String version,
            String installationSupport) {
        /**
         * Construye una entrada compatible con consumidores que no aportan plataforma, versión ni
         * soporte de instalación.
         *
         * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
         * @param appId UUID de la aplicación seleccionada en el catálogo.
         * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
         *     candidato automático.
         * @param appName Nombre de la aplicación que se muestra en el manifiesto o las
         *     instrucciones manuales.
         * @param filename Nombre seguro del instalador utilizado al escribir archivos y preparar el
         *     manifiesto.
         * @param status Estado del resultado representado, distinto del estado de validación de la
         *     fuente.
         * @param sizeBytes Número de bytes del archivo descargado; null en un elemento de
         *     manifiesto sin archivo.
         * @param sha256 SHA-256 calculado del contenido descargado, cuando existe un archivo.
         * @param archivePath Nombre relativo del archivo dentro del ZIP para localizar el
         *     instalador desde el manifiesto.
         * @param objectKey Clave del artefacto en almacenamiento de objetos; no contiene la URL
         *     final del proveedor.
         * @param error Diagnóstico seguro del elemento fallido; null cuando no hubo fallo.
         * @param manualShortcut Ruta de la entrada que contiene instrucciones o acceso manual,
         *     cuando se generó.
         */
        public ManifestItem(UUID itemId, UUID appId, UUID sourceRef, String appName,
                String filename, String status, Long sizeBytes, String sha256,
                String archivePath, String objectKey, String error, String manualShortcut) {
            this(itemId, appId, sourceRef, appName, filename, status, sizeBytes, sha256,
                    archivePath, objectKey, error, manualShortcut, null, null, null, null);
        }
    }

    /**
     * Describe los resultados del trabajo completo mediante un contrato versionado que permite
     * localizar y comprobar las entradas del archivo.
     *
     * @param manifestVersion Versión del contrato del manifiesto, independiente de la versión de la
     *     aplicación.
     * @param jobId UUID del trabajo al que pertenecen todas las entradas del manifiesto.
     * @param generatedAt Instante de creación del manifiesto del trabajo.
     * @param status Estado del resultado representado, distinto del estado de validación de la
     *     fuente.
     * @param items Resultados por elemento en el orden preparado para el archivo.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Datos de descarga
     */
    public record DownloadManifest(
            int manifestVersion,
            UUID jobId,
            Instant generatedAt,
            String status,
            List<ManifestItem> items) {
    }
}
