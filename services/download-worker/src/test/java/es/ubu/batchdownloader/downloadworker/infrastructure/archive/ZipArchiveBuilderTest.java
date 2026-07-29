package es.ubu.batchdownloader.downloadworker.infrastructure.archive;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipArchiveBuilderTest {
    @TempDir
    Path temp;

    @Test
    void createsArchiveWithArtifactsAndManifest() throws Exception {
        Path installer = temp.resolve("App.exe");
        Path manifest = temp.resolve("manifest-source.json");
        Files.writeString(installer, "binary");
        Files.writeString(manifest, "{\"status\":\"completed\"}");
        Path zip = temp.resolve("bundle.zip");

        new ZipArchiveBuilder().build(zip, List.of(new DownloadedArtifact(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "App.exe", installer, 6, "sha", "key")), List.of(), manifest);

        try (ZipFile opened = new ZipFile(zip.toFile())) {
            assertThat(opened.getEntry("App.exe")).isNotNull();
            assertThat(opened.getEntry("manifest.json")).isNotNull();
            assertThat(new String(opened.getInputStream(opened.getEntry("App.exe")).readAllBytes()))
                    .isEqualTo("binary");
        }
    }
}
