package es.ubu.batchdownloader.downloadworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.InstallationMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;

/** Materializa un instalador offline usando solo descargas verificadas y recetas declarativas. */
final class LinuxInstallerBundleWriter {
    private final ObjectMapper mapper;
    private static final String ROOT = "/linux-installer/";
    private static final Set<String> AUTOMATIC = Set.of("deb", "rpm", "arch", "appimage", "tarball", "jar");

    LinuxInstallerBundleWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    static Map<String, Object> profile(InstallationMetadata metadata) {
        if (metadata.profile() != null) return new LinkedHashMap<>(metadata.profile());
        String strategy = switch (metadata.extension() == null ? "" : metadata.extension().toLowerCase(Locale.ROOT)) {
            case ".deb" -> "deb";
            case ".rpm" -> "rpm";
            case ".pkg.tar.zst" -> "arch";
            case ".appimage" -> "appimage";
            default -> "manual";
        };
        return new LinkedHashMap<>(Map.of("schemaVersion", 1, "strategy", strategy, "scope", "auto"));
    }

    static String support(InstallationMetadata metadata) {
        if (!"linux".equalsIgnoreCase(metadata.operatingSystem())) return "not_applicable";
        Map<String, Object> profile = profile(metadata);
        boolean signatureAvailable = !profile.containsKey("verification") || metadata.signatureBase64() != null;
        return AUTOMATIC.contains(profile.get("strategy")) && signatureAvailable ? "automatic" : "manual";
    }

    Map<String, byte[]> write(UUID jobId, List<DownloadedArtifact> artifacts, byte[] manifest) {
        List<DownloadedArtifact> linux = artifacts.stream().filter(a -> a.installation() != null
                && "linux".equalsIgnoreCase(a.installation().operatingSystem())).toList();
        if (linux.isEmpty()) return Map.of();
        try {
            Map<String, byte[]> entries = runtime();
            Map<String, DownloadedArtifact> available = new LinkedHashMap<>();
            linux.forEach(a -> available.put(a.appId().toString(), a));
            List<String> components = new ArrayList<>();
            for (DownloadedArtifact artifact : linux) {
                var metadata = artifact.installation();
                Map<String, Object> profile = profile(metadata);
                List<String> dependencies = profile.get("dependencies") instanceof List<?> values
                        ? values.stream().map(Object::toString).toList() : List.of();
                boolean missingDependency = dependencies.stream().anyMatch(d -> !available.containsKey(d));
                profile.put("dependencies", dependencies.stream().filter(available::containsKey).toList());
                String reason = missingDependency ? "dependency_not_downloaded"
                        : "automatic".equals(support(metadata)) ? null : "recipe_or_signature_required";
                if (reason != null) profile.put("strategy", "manual");
                String identifier = artifact.appId().toString();
                Map<String, Object> component = new LinkedHashMap<>();
                component.put("schemaVersion", 1);
                component.put("id", identifier);
                component.put("appId", identifier);
                component.put("sourceRef", artifact.sourceRef().toString());
                component.put("name", safeText(metadata.appName() == null ? identifier : metadata.appName()));
                component.put("version", safeText(metadata.version() == null ? "unknown" : metadata.version()));
                component.put("architecture", metadata.architecture());
                component.put("filename", artifact.filename());
                component.put("sha256", artifact.sha256());
                component.put("sizeBytes", artifact.sizeBytes());
                component.put("profile", profile);
                if (reason != null) component.put("manualReason", reason);
                if (metadata.signatureBase64() != null) {
                    byte[] signature = Base64.getDecoder().decode(metadata.signatureBase64());
                    if (signature.length > 1024 * 1024) throw new IOException("signature_too_large");
                    String signaturePath = "signatures/" + identifier + ".asc";
                    entries.put(signaturePath, signature);
                    component.put("signatureFile", signaturePath);
                }
                entries.put("config/components/" + identifier + ".json", json(component));
                components.add(identifier);
            }
            entries.put("config/packages.conf", bytes("# schemaVersion=1\n" + String.join("\n", components) + "\n"));
            entries.put("config/bundle.json", json(Map.of("schemaVersion", 1, "id", jobId.toString())));
            StringBuilder checksums = new StringBuilder();
            for (var entry : entries.entrySet()) checksum(checksums, entry.getKey(), sha256(entry.getValue()));
            for (var artifact : artifacts) checksum(checksums, artifact.filename(), artifact.sha256());
            checksum(checksums, "manifest.json", sha256(manifest));
            entries.put("checksums.sha256", bytes(checksums.toString()));
            return entries;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InfrastructureException("linux_installer_creation_failed", exception);
        }
    }

    private Map<String, byte[]> runtime() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : new String(resource("resources.list"), StandardCharsets.UTF_8).split("\\R")) {
            if (!name.isBlank() && !name.startsWith("#")) {
                entries.put(name, bytes(new String(resource(name), StandardCharsets.UTF_8).replace("\r\n", "\n")));
            }
        }
        return entries;
    }

    private byte[] resource(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream(ROOT + name)) {
            if (stream == null) throw new IOException("missing_runtime_resource");
            return stream.readAllBytes();
        }
    }

    private byte[] json(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String safeText(String text) {
        String clean = text.replaceAll("\\p{Cntrl}", " ");
        return clean.substring(0, Math.min(clean.length(), 200));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void checksum(StringBuilder target, String filename, String hash) {
        target.append(hash).append("  ").append(filename).append('\n');
    }
}
