package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
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

/** Genera accesos directos manuales sin conservar URLs con credenciales. */
final class ManualShortcutWriter {
    private static final Set<String> SENSITIVE_QUERY_NAMES = Set.of(
            "access_token", "api_key", "apikey", "auth", "authorization", "key",
            "password", "sig", "signature", "token");
    private static final Pattern SENSITIVE_QUERY_MARKER = Pattern.compile(
            "access_?key|api_?key|authorization|credential|password|secret|signature|token");

    private final JobItemMetadataLookup metadataLookup;
    private final FilenamePolicy filenamePolicy;
    private final PublicHttpsUriPolicy publicHttpsUriPolicy;

    ManualShortcutWriter(
            JobItemMetadataLookup metadataLookup,
            FilenamePolicy filenamePolicy,
            PublicHttpsUriPolicy publicHttpsUriPolicy) {
        this.metadataLookup = metadataLookup;
        this.filenamePolicy = filenamePolicy;
        this.publicHttpsUriPolicy = publicHttpsUriPolicy;
    }

    Result write(
            DownloadJobRequestedEvent event,
            List<FailedDownload> failures,
            Path jobDirectory) {
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
            writeShortcut(shortcutsDirectory, shortcut, officialPage);
            String archivePath = "Descargas manuales/" + filename;
            entries.add(new ArchiveEntry(archivePath, shortcut));
            pathsByItem.put(failure.itemId(), archivePath);
        }
        return new Result(List.copyOf(entries), Map.copyOf(pathsByItem), metadata);
    }

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

    private void writeShortcut(Path directory, Path shortcut, URI officialPage) {
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    shortcut,
                    "[InternetShortcut]\r\nURL=" + officialPage.toASCIIString() + "\r\n",
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new InfrastructureException("manual_shortcut_creation_failed", exception);
        }
    }

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

    record Result(
            List<ArchiveEntry> entries,
            Map<UUID, String> pathsByItem,
            Map<UUID, DownloadItemMetadata> metadata) {}
}
