package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ofrece páginas oficiales como accesos manuales para elementos sin instalador, tras comprobar
 * destino público y descartar consultas con nombres de parámetros sensibles.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy
 * @see es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup
 * @see es.ubu.batchdownloader.downloadworker.application.FilenamePolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
public final class ManualShortcutWriter {
    private static final Set<String> SENSITIVE_QUERY_NAMES = Set.of(
            "access_token", "api_key", "apikey", "auth", "authorization", "key",
            "password", "sig", "signature", "token");
    private static final Pattern SENSITIVE_QUERY_MARKER = Pattern.compile(
            "access_?key|api_?key|authorization|credential|password|secret|signature|token");

    private final JobItemMetadataLookup metadataLookup;
    private final FilenamePolicy filenamePolicy;
    private final PublicUriPolicy publicHttpsUriPolicy;

    /**
     * Compone consulta de metadatos, nombres seguros y validación de URI mediante el puerto
     * público.
     *
     * @param metadataLookup Consulta por lotes de nombres y páginas oficiales de los elementos
     *     fallidos.
     * @param filenamePolicy Asigna nombres seguros y únicos dentro del archivo del trabajo.
     * @param publicHttpsUriPolicy Puerto de validación de destinos públicos; evita depender del
     *     adaptador HTTP concreto.
     */
    public ManualShortcutWriter(
            JobItemMetadataLookup metadataLookup,
            FilenamePolicy filenamePolicy,
            PublicUriPolicy publicHttpsUriPolicy) {
        this.metadataLookup = metadataLookup;
        this.filenamePolicy = filenamePolicy;
        this.publicHttpsUriPolicy = publicHttpsUriPolicy;
    }

    /**
     * Consulta los elementos fallidos y crea accesos .url solo para páginas admisibles,
     * asignándoles nombres únicos bajo Descargas manuales.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param failures Rechazos de resolución o descarga que pueden ofrecer una alternativa manual.
     * @param jobDirectory Directorio temporal exclusivo de esta ejecución del trabajo.
     * @return entradas creadas, rutas por elemento y metadatos consultados.
     */
    Result write(
            DownloadJobRequestedEvent event,
            List<FailedDownload> failures,
            Path jobDirectory,
            DownloadBudget budget) {
        Map<UUID, DownloadItemMetadata> metadata = metadata(event, failures);
        if (failures.isEmpty()) {
            return new Result(List.of(), Map.of(), metadata);
        }
        List<ArchiveEntry> entries = new ArrayList<>();
        Map<UUID, String> pathsByItem = new HashMap<>();
        Set<String> usedNames = filenamePolicy.newNameSet();
        Path shortcutsDirectory = jobDirectory.resolve("manual-shortcuts");
        for (FailedDownload failure : failures) {
            DownloadItemMetadata item = metadata.get(failure.itemId());
            URI officialPage = safeOfficialPage(item == null ? null : item.officialPageUrl());
            if (officialPage == null) {
                continue;
            }
            String filename = filenamePolicy.manualShortcutFilename(item.appName(), usedNames);
            Path shortcut = shortcutsDirectory.resolve(filename);
            writeShortcut(shortcutsDirectory, shortcut, officialPage, budget);
            String archivePath = "Descargas manuales/" + filename;
            entries.add(new ArchiveEntry(archivePath, shortcut));
            pathsByItem.put(failure.itemId(), archivePath);
        }
        return new Result(List.copyOf(entries), Map.copyOf(pathsByItem), metadata);
    }

    /**
     * Selecciona de la solicitud original solo los UUID fallidos y consulta sus metadatos en lote;
     * evita consultas cuando no hay fallos.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param failures Rechazos de resolución o descarga que pueden ofrecer una alternativa manual.
     * @return metadatos por UUID del elemento.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si los UUID
     *     de los fallos no corresponden a los elementos admitidos.
     */
    private Map<UUID, DownloadItemMetadata> metadata(
            DownloadJobRequestedEvent event,
            List<FailedDownload> failures) {
        if (failures.isEmpty()) {
            return Map.of();
        }
        Set<UUID> failedItemIds = failures.stream()
                .map(FailedDownload::itemId)
                .collect(java.util.stream.Collectors.toSet());
        List<DownloadItemRequest> failedItems = event.payload().items().stream()
                .filter(item -> failedItemIds.contains(item.itemId()))
                .toList();
        if (failedItems.size() != failedItemIds.size()) {
            throw new InfrastructureException(
                    "invalid_failed_download_items",
                    new IllegalStateException("Failed items do not match the job command"));
        }
        return metadataLookup.find(event.payload().jobId(), failedItems);
    }

    /**
     * Crea el directorio de accesos y escribe un archivo InternetShortcut UTF-8 con URI ASCII y
     * finales CRLF.
     *
     * @param directory Directorio temporal exclusivo en el que se escribirán los instaladores.
     * @param shortcut Ruta local del archivo .url que se crea.
     * @param officialPage URI oficial que ya superó la validación de acceso público y de parámetros
     *     sensibles.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no se
     *     puede crear el directorio o escribir el acceso.
     */
    private void writeShortcut(Path directory, Path shortcut, URI officialPage, DownloadBudget budget) {
        try {
            Files.createDirectories(directory);
            byte[] bytes = ("[InternetShortcut]\r\nURL=" + officialPage.toASCIIString() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            budget.consume(bytes.length);
            Files.write(shortcut, bytes);
        } catch (IOException exception) {
            throw new InfrastructureException("manual_shortcut_creation_failed", exception);
        }
    }

    /**
     * Descarta texto ausente o con controles, valida la sintaxis y exige consulta sin marcadores
     * sensibles y un destino permitido por la política pública.
     *
     * @param value Texto propuesto que se normaliza o valida antes de incluirlo en el archivo.
     * @return URI admisible o null para páginas rechazadas.
     */
    private URI safeOfficialPage(String value) {
        if (value == null
                || value.isBlank()
                || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            if (hasSensitiveQuery(uri)) {
                return null;
            }
            publicHttpsUriPolicy.validate(uri);
            return uri;
        } catch (IllegalArgumentException | DownloadRejectedException exception) {
            return null;
        }
    }

    /**
     * Decodifica y normaliza los nombres de parámetros y detecta nombres o marcadores sensibles;
     * una codificación inválida también causa rechazo.
     *
     * @param uri URI cuya consulta se revisa antes de incorporarla a un acceso manual.
     * @return true cuando la consulta debe excluirse del acceso manual.
     */
    private boolean hasSensitiveQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return false;
        }
        try {
            for (String parameter : query.split("&")) {
                String rawName = parameter.split("=", 2)[0];
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
                if (SENSITIVE_QUERY_NAMES.contains(name)
                        || SENSITIVE_QUERY_MARKER.matcher(name).find()) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    /**
     * Relaciona accesos manuales con sus elementos y conserva metadatos para describir los fallos
     * en el manifiesto.
     *
     * @param entries Archivos .url creados y sus ubicaciones previstas dentro del ZIP.
     * @param pathsByItem Ruta de cada acceso manual por UUID del elemento al que corresponde.
     * @param metadata Metadatos públicos por UUID de elemento consultado.
     * @since 0.1.0
     * @version 0.1.0
     * @category Resultados y empaquetado
     */
    record Result(
            List<ArchiveEntry> entries,
            Map<UUID, String> pathsByItem,
            Map<UUID, DownloadItemMetadata> metadata) {}
}
