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

/**
 * Prepara runtime, recetas y huellas del instalador offline a partir de artefactos Linux ya
 * descargados, degradando a instalación manual cuando faltan dependencias o firmas requeridas.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @see es.ubu.batchdownloader.downloadworker.domain.DownloadModels.InstallationMetadata
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
public final class LinuxInstallerBundleWriter {
    private final ObjectMapper mapper;
    private static final String ROOT = "/linux-installer/";
    private static final String MANUAL = "manual";
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String STRATEGY = "strategy";
    private static final int MAX_SIGNATURE_BYTES = 1024 * 1024;
    private static final Set<String> AUTOMATIC = Set.of("deb", "rpm", "arch", "appimage", "tarball", "jar");

    /**
     * Conecta la serialización de componentes y configuración del bundle Linux.
     *
     * @param mapper Serializador de recetas y configuración del runtime Linux.
     */
    public LinuxInstallerBundleWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Copia la receta existente o infiere una receta mínima por formato para deb, rpm, paquetes
     * Arch y AppImage; otros formatos quedan como manuales.
     *
     * @param metadata Metadatos de instalación del candidato que conserva plataforma, formato,
     *     receta y firma.
     * @return mapa mutable de receta independiente del mapa recibido.
     */
    static Map<String, Object> profile(InstallationMetadata metadata) {
        if (metadata.profile() != null) return new LinkedHashMap<>(metadata.profile());
        String strategy = switch (metadata.extension() == null ? "" : metadata.extension().toLowerCase(Locale.ROOT)) {
            case ".deb" -> "deb";
            case ".rpm" -> "rpm";
            case ".pkg.tar.zst" -> "arch";
            case ".appimage" -> "appimage";
            default -> MANUAL;
        };
        return new LinkedHashMap<>(Map.of(SCHEMA_VERSION, 1, STRATEGY, strategy, "scope", "auto"));
    }

    /**
     * Comprueba plataforma, estrategia reconocida y disponibilidad de firma cuando la receta exige
     * verificación.
     *
     * @param metadata Metadatos de instalación del candidato que conserva plataforma, formato,
     *     receta y firma.
     * @return not_applicable fuera de Linux; automatic o manual dentro de Linux.
     */
    static String support(InstallationMetadata metadata) {
        if (!"linux".equalsIgnoreCase(metadata.operatingSystem())) return "not_applicable";
        Map<String, Object> profile = profile(metadata);
        boolean signatureAvailable = !profile.containsKey("verification") || metadata.signatureBase64() != null;
        return AUTOMATIC.contains(profile.get(STRATEGY)) && signatureAvailable ? "automatic" : MANUAL;
    }

    /**
     * Genera componentes con fuente exacta, recetas y firmas y un índice de huellas para runtime,
     * instaladores y manifiesto. Las dependencias ausentes o la falta de firma requerida convierten
     * la receta en manual.
     *
     * @param jobId UUID del trabajo cuya cancelación se comprueba durante la espera.
     * @param artifacts Instaladores descargados cuya integridad ya está calculada.
     * @param manifest Bytes del manifiesto preparado que se escriben tanto dentro como fuera del
     *     ZIP.
     * @return entradas pequeñas del runtime; mapa vacío si no hay artefactos Linux.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si faltan
     *     recursos, falla JSON, una firma no es Base64 válido o supera un MiB decodificada.
     */
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
                components.add(writeComponent(artifact, available, entries));
            }
            writeBundleEntries(entries, jobId, components, artifacts, manifest);
            return entries;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InfrastructureException("linux_installer_creation_failed", exception);
        }
    }

    private String writeComponent(
            DownloadedArtifact artifact,
            Map<String, DownloadedArtifact> available,
            Map<String, byte[]> entries) throws IOException {
        InstallationMetadata metadata = artifact.installation();
        Map<String, Object> profile = profile(metadata);
        List<String> dependencies = dependencies(profile);
        boolean missingDependency = dependencies.stream().anyMatch(d -> !available.containsKey(d));
        profile.put("dependencies", dependencies.stream().filter(available::containsKey).toList());
        String reason = manualReason(metadata, missingDependency);
        if (reason != null) {
            profile.put(STRATEGY, MANUAL);
        }
        String identifier = artifact.appId().toString();
        Map<String, Object> component = component(artifact, metadata, identifier, profile, reason);
        writeSignature(metadata, identifier, entries, component);
        entries.put("config/components/" + identifier + ".json", json(component));
        return identifier;
    }

    private static List<String> dependencies(Map<String, Object> profile) {
        Object value = profile.get("dependencies");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(Object::toString).toList();
    }

    private static String manualReason(InstallationMetadata metadata, boolean missingDependency) {
        if (missingDependency) {
            return "dependency_not_downloaded";
        }
        if ("automatic".equals(support(metadata))) {
            return null;
        }
        return "recipe_or_signature_required";
    }

    private static Map<String, Object> component(
            DownloadedArtifact artifact,
            InstallationMetadata metadata,
            String identifier,
            Map<String, Object> profile,
            String reason) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put(SCHEMA_VERSION, 1);
        component.put("id", identifier);
        component.put("appId", identifier);
        component.put("sourceRef", artifact.sourceRef().toString());
        component.put("name", safeText(valueOrDefault(metadata.appName(), identifier)));
        component.put("version", safeText(valueOrDefault(metadata.version(), "unknown")));
        component.put("architecture", metadata.architecture());
        component.put("filename", artifact.filename());
        component.put("sha256", artifact.sha256());
        component.put("sizeBytes", artifact.sizeBytes());
        component.put("profile", profile);
        if (reason != null) {
            component.put("manualReason", reason);
        }
        return component;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void writeSignature(
            InstallationMetadata metadata,
            String identifier,
            Map<String, byte[]> entries,
            Map<String, Object> component) throws IOException {
        if (metadata.signatureBase64() == null) {
            return;
        }
        byte[] signature = Base64.getDecoder().decode(metadata.signatureBase64());
        if (signature.length > MAX_SIGNATURE_BYTES) {
            throw new IOException("signature_too_large");
        }
        String signaturePath = "signatures/" + identifier + ".asc";
        entries.put(signaturePath, signature);
        component.put("signatureFile", signaturePath);
    }

    private void writeBundleEntries(
            Map<String, byte[]> entries,
            UUID jobId,
            List<String> components,
            List<DownloadedArtifact> artifacts,
            byte[] manifest) throws IOException {
        entries.put("config/packages.conf", bytes("# schemaVersion=1\n" + String.join("\n", components) + "\n"));
        entries.put("config/bundle.json", json(Map.of(SCHEMA_VERSION, 1, "id", jobId.toString())));
        StringBuilder checksums = new StringBuilder();
        for (var entry : entries.entrySet()) checksum(checksums, entry.getKey(), sha256(entry.getValue()));
        for (var artifact : artifacts) checksum(checksums, artifact.filename(), artifact.sha256());
        checksum(checksums, "manifest.json", sha256(manifest));
        entries.put("checksums.sha256", bytes(checksums.toString()));
    }

    /**
     * Carga los recursos enumerados por resources.list omitiendo líneas vacías y comentarios y
     * convierte finales CRLF a LF.
     *
     * @return entradas del runtime en el orden del inventario.
     * @throws java.io.IOException si no puede cargar el inventario o un recurso enumerado.
     */
    private Map<String, byte[]> runtime() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : new String(resource("resources.list"), StandardCharsets.UTF_8).split("\\R")) {
            if (!name.isBlank() && !name.startsWith("#")) {
                entries.put(name, bytes(new String(resource(name), StandardCharsets.UTF_8).replace("\r\n", "\n")));
            }
        }
        return entries;
    }

    /**
     * Lee completamente un recurso propio del runtime y cierra su flujo de classpath.
     *
     * @param name Ruta del recurso propio relativa al directorio linux-installer del classpath.
     * @return bytes del recurso solicitado.
     * @throws java.io.IOException si el recurso no existe o no puede leerse.
     */
    private byte[] resource(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream(ROOT + name)) {
            if (stream == null) throw new IOException("missing_runtime_resource");
            return stream.readAllBytes();
        }
    }

    /**
     * Serializa configuración declarativa con formato legible para el runtime offline.
     *
     * @param value Texto propuesto que se normaliza o valida antes de incluirlo en el archivo.
     * @return bytes del JSON.
     * @throws java.io.IOException si falla la serialización.
     */
    private byte[] json(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    /**
     * Codifica en UTF-8 el texto de una entrada del runtime.
     *
     * @param text Texto que se convierte a bytes o se limita para incluirlo en una configuración.
     * @return bytes que se escribirán y usarán para calcular su huella.
     */
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sustituye caracteres de control por espacios y limita el nombre o versión visible a
     * doscientos caracteres.
     *
     * @param text Texto que se convierte a bytes o se limita para incluirlo en una configuración.
     * @return texto acotado para la configuración del componente.
     */
    private static String safeText(String text) {
        String clean = text.replaceAll("\\p{Cntrl}", " ");
        return clean.substring(0, Math.min(clean.length(), 200));
    }

    /**
     * Calcula la integridad de una entrada pequeña ya materializada.
     *
     * @param content Bytes de la entrada cuya huella se calcula.
     * @return SHA-256 hexadecimal de sus bytes.
     * @throws IllegalStateException si no está disponible SHA-256 en el proveedor criptográfico.
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Añade una línea de huella y nombre con dos espacios de separación al índice de integridad.
     *
     * @param target Acumulador del archivo checksums.sha256.
     * @param filename Nombre de archivo que se separa o deduplica conservando su extensión.
     * @param hash SHA-256 hexadecimal previamente calculado para la entrada.
     */
    private static void checksum(StringBuilder target, String filename, String hash) {
        target.append(hash).append("  ").append(filename).append('\n');
    }
}
