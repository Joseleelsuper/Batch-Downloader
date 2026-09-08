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

/** Verifica el instalador offline que se incorpora a los ZIP con ejecutables Linux. */
class LinuxInstallerBundleWriterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temp;

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
                "checksums.sha256");
        assertThat(entries.get("signatures/" + applicationId + ".asc")).isEqualTo(signature);
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
