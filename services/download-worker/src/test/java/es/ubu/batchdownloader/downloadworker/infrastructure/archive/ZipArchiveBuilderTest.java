package es.ubu.batchdownloader.downloadworker.infrastructure.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agrupa los escenarios de prueba de {@code ZipArchiveBuilderTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class ZipArchiveBuilderTest {
    /**
     * Dato compartido {@code temp} para los escenarios de prueba.
     */
    @TempDir
    Path temp;

    /**
     * Comprueba el escenario {@code createsArchiveWithArtifactsAndManifest}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void createsArchiveWithArtifactsAndManifest() throws Exception {
        Path installer = temp.resolve("App.exe");
        Files.writeString(installer, "binary");
        Path zip = temp.resolve("bundle.zip");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new ZipArchiveBuilder().build(output, 1, writer -> {
            writer.add("App.exe", installer);
            writer.add("manifest.json", "{\"status\":\"completed\"}".getBytes());
        });
        Files.write(zip, output.toByteArray());

        try (ZipFile opened = new ZipFile(zip.toFile())) {
            assertThat(opened.getEntry("App.exe")).isNotNull();
            assertThat(opened.getEntry("manifest.json")).isNotNull();
            assertThat(new String(opened.getInputStream(opened.getEntry("App.exe")).readAllBytes()))
                    .isEqualTo("binary");
        }
    }
}
