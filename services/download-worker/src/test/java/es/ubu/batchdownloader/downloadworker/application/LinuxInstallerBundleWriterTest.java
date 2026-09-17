package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.InstallationMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprueba recetas, firmas y huellas del runtime offline y la degradación manual cuando el ZIP no
 * contiene requisitos suficientes.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.LinuxInstallerBundleWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class LinuxInstallerBundleWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temp;

    /**
     * Incluye aplicación firmada y dependencia Linux y comprueba recursos del runtime, bytes de
     * firma, relación de dependencia, ausencia de URL privada y nombres del índice de integridad.
     */
    @Test
    void writesRuntimeComponentsSignatureAndCompleteChecksums() throws Exception {
        UUID dependencyId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        byte[] signature = "detached-signature".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> profile = Map.of(
                "schemaVersion", 1,
                "strategy", "deb",
                "scope", "system",
                "dependencies", List.of(dependencyId.toString()),
                "verification", Map.of("fingerprint", "a".repeat(40)));
        DownloadedArtifact dependency = artifact(
                dependencyId,
                "dependency.AppImage",
                new InstallationMetadata(
                        "Dependency", "2.0", ".appimage", "linux", "x86_64", null, null));
        DownloadedArtifact application = artifact(
                applicationId,
                "application.deb",
                new InstallationMetadata(
                        "Application", "1.0", ".deb", "linux", "x86_64", profile,
                        Base64.getEncoder().encodeToString(signature)));

        Map<String, byte[]> entries = new LinuxInstallerBundleWriter(MAPPER).write(
                UUID.randomUUID(), List.of(application, dependency), "manifest".getBytes(StandardCharsets.UTF_8));

        assertThat(entries).containsKeys(
                "install.sh",
                "uninstall.sh",
                "installer.conf",
                "lib/common.sh",
                "config/packages.conf",
                "config/components/" + applicationId + ".json",
                "signatures/" + applicationId + ".asc",
                "checksums.sha256")
                .containsEntry("signatures/" + applicationId + ".asc", signature);
        assertThat(new String(entries.get("config/packages.conf"), StandardCharsets.UTF_8))
                .containsSubsequence(applicationId.toString(), dependencyId.toString());

        JsonNode component = MAPPER.readTree(entries.get("config/components/" + applicationId + ".json"));
        assertThat(component.path("profile").path("strategy").asText()).isEqualTo("deb");
        assertThat(component.path("profile").path("dependencies").get(0).asText())
                .isEqualTo(dependencyId.toString());
        assertThat(component.has("url")).isFalse();

        String checksums = new String(entries.get("checksums.sha256"), StandardCharsets.UTF_8);
        assertThat(checksums)
                .contains("  application.deb\n")
                .contains("  dependency.AppImage\n")
                .contains("  manifest.json\n")
                .contains("  install.sh\n");
    }

    /**
     * Aporta una receta con dependencia ausente y sin firma requerida y comprueba estrategia
     * manual, retirada de la dependencia no incluida y motivo de degradación.
     */
    @Test
    void downgradesToManualWhenAnApprovedDependencyOrSignatureIsMissing() throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID missingDependency = UUID.randomUUID();
        DownloadedArtifact application = artifact(
                applicationId,
                "application.deb",
                new InstallationMetadata(
                        "Application", "1.0", ".deb", "linux", "x86_64",
                        Map.of(
                                "schemaVersion", 1,
                                "strategy", "deb",
                                "scope", "system",
                                "dependencies", List.of(missingDependency.toString()),
                                "verification", Map.of("fingerprint", "b".repeat(40))),
                        null));

        Map<String, byte[]> entries = new LinuxInstallerBundleWriter(MAPPER).write(
                UUID.randomUUID(), List.of(application), new byte[0]);

        JsonNode component = MAPPER.readTree(entries.get("config/components/" + applicationId + ".json"));
        assertThat(component.path("profile").path("strategy").asText()).isEqualTo("manual");
        assertThat(component.path("profile").path("dependencies")).isEmpty();
        assertThat(component.path("manualReason").asText()).isEqualTo("dependency_not_downloaded");
        assertThat(LinuxInstallerBundleWriter.support(application.installation())).isEqualTo("manual");
    }

    /**
     * Proporciona únicamente un instalador Windows y comprueba que no se generan entradas del
     * runtime Linux.
     */
    @Test
    void omitsTheInstallerForAJobWithoutLinuxArtifacts() throws Exception {
        DownloadedArtifact windows = artifact(
                UUID.randomUUID(),
                "application.exe",
                new InstallationMetadata(
                        "Application", "1.0", ".exe", "windows", "x86_64", null, null));

        assertThat(new LinuxInstallerBundleWriter(MAPPER).write(
                UUID.randomUUID(), List.of(windows), new byte[0])).isEmpty();
    }

    /**
     * Crea un archivo local pequeño y metadatos controlados para verificar la configuración del
     * bundle.
     *
     * @param appId UUID de la aplicación representada por el artefacto de prueba.
     * @param filename Nombre propuesto que se conserva para probar saneamiento o configuración del
     *     instalador.
     * @param metadata Receta, plataforma y firma controladas por el escenario de instalación.
     * @return artefacto de prueba con una huella declarada fija.
     * @throws java.lang.Exception si no puede escribirse el archivo temporal del escenario.
     */
    private DownloadedArtifact artifact(UUID appId, String filename, InstallationMetadata metadata)
            throws Exception {
        Path payload = temp.resolve(filename);
        byte[] content = ("payload-" + appId).getBytes(StandardCharsets.UTF_8);
        Files.write(payload, content);
        return new DownloadedArtifact(
                UUID.randomUUID(), appId, UUID.randomUUID(), filename, payload,
                content.length, "a".repeat(64), null, metadata);
    }
}
