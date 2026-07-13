package es.ubu.batchdownloader.downloadworker.infrastructure.archive;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipArchiveBuilder implements ArchiveBuilder {
    @Override
    public void build(Path target, List<DownloadedArtifact> artifacts, Path manifest) {
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target);
                    ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (DownloadedArtifact artifact : artifacts) {
                    add(zip, artifact.filename(), artifact.path());
                }
                add(zip, "manifest.json", manifest);
            }
        } catch (IOException exception) {
            throw new InfrastructureException("zip_creation_failed", exception);
        }
    }

    private void add(ZipOutputStream zip, String filename, Path source) throws IOException {
        zip.putNextEntry(new ZipEntry(filename));
        try (InputStream input = Files.newInputStream(source)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }
}
