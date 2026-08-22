package es.ubu.batchdownloader.translation.infrastructure.file;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.config.TranslationProperties;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/**
 * Implementa el componente {@code JsonFileLocaleCatalog}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class JsonFileLocaleCatalog implements LocaleCatalog {

    /**
     * Constante que define {@code SPANISH_LOCALE}.
     */
    private static final String SPANISH_LOCALE = "es";
    /**
     * Constante que define {@code TEMPLATE_DIRECTORY}.
     */
    private static final String TEMPLATE_DIRECTORY = "template";
    /**
     * Constante que define {@code SPANISH_DIRECTORY}.
     */
    private static final String SPANISH_DIRECTORY = "es";

    /**
     * Estado {@code cache} mantenido por {@code JsonFileLocaleCatalog}.
     */
    private final Map<String, LocaleDocument> cache;

    /**
     * Inicializa una instancia de {@code JsonFileLocaleCatalog}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public JsonFileLocaleCatalog(TranslationProperties properties) {
        Path localesPath = properties.localesPath();
        ObjectMapper strictMapper = strictObjectMapper();
        Map<String, ObjectNode> templatePages = readPages(
                localesPath.resolve(TEMPLATE_DIRECTORY), strictMapper);
        Map<String, ObjectNode> spanishPages = readPages(
                localesPath.resolve(SPANISH_DIRECTORY), strictMapper);
        validateTemplate(templatePages);
        validateSpanishLocale(templatePages, spanishPages);
        ObjectNode spanish = mergePages(spanishPages, strictMapper, SPANISH_DIRECTORY);
        byte[] spanishContent = writeBytes(spanish, strictMapper);
        cache = Map.of(
                SPANISH_LOCALE,
                new LocaleDocument(SPANISH_LOCALE, spanishContent, calculateEtag(spanishContent)));
    }

    /**
     * Busca el resultado solicitado mediante {@code findByLocale}.
     *
     * @param locale Valor de {@code locale} utilizado por la operación.
     * @return Resultado producido por {@code findByLocale}.
     */
    @Override
    public Optional<LocaleDocument> findByLocale(String locale) {
        return Optional.ofNullable(cache.get(locale));
    }

    /**
     * Ejecuta la operación {@code strictObjectMapper}.
     *
     * @return Resultado producido por {@code strictObjectMapper}.
     */
    private ObjectMapper strictObjectMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    /**
     * Lee los archivos de página de un catálogo.
     *
     * @param directory Directorio del catálogo que debe procesarse.
     * @param mapper Valor de {@code mapper} utilizado por la operación.
     * @return Páginas indexadas por nombre de archivo.
     */
    private Map<String, ObjectNode> readPages(Path directory, ObjectMapper mapper) {
        if (!Files.isDirectory(directory)) {
            throw new LocaleCatalogConfigurationException(
                    "No existe el directorio de traducciones requerido: " + directory.getFileName());
        }
        List<Path> pageFiles;
        try (Stream<Path> paths = Files.list(directory)) {
            pageFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo listar el directorio de traducciones " + directory.getFileName(),
                    exception);
        }
        if (pageFiles.isEmpty()) {
            throw new LocaleCatalogConfigurationException(
                    "El directorio de traducciones no contiene páginas JSON: "
                            + directory.getFileName());
        }
        Map<String, ObjectNode> pages = new LinkedHashMap<>();
        for (Path pageFile : pageFiles) {
            String fileName = pageFile.getFileName().toString();
            pages.put(fileName, readObject(readBytes(pageFile), fileName, mapper));
        }
        return pages;
    }

    /**
     * Ejecuta la operación {@code readObject}.
     *
     * @param content Contenido que debe procesarse.
     * @param fileName Valor de {@code fileName} utilizado por la operación.
     * @param mapper Valor de {@code mapper} utilizado por la operación.
     * @return Resultado producido por {@code readObject}.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private ObjectNode readObject(byte[] content, String fileName, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(content);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new LocaleCatalogConfigurationException(
                        fileName + " debe contener un objeto JSON en la raíz");
            }
            return objectNode;
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo interpretar " + fileName + " como JSON estricto", exception);
        }
    }

    /**
     * Ejecuta la operación {@code readBytes}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @return Resultado producido por {@code readBytes}.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private byte[] readBytes(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new LocaleCatalogConfigurationException(
                    "No existe el fichero de traducciones requerido: " + path.getFileName());
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo leer el fichero de traducciones " + path.getFileName(), exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateTemplate}.
     *
     * @param templatePages Páginas que definen el contrato de traducciones.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateTemplate(Map<String, ObjectNode> templatePages) {
        for (Map.Entry<String, ObjectNode> page : templatePages.entrySet()) {
            if (page.getValue().isEmpty()) {
                throw new LocaleCatalogConfigurationException(
                        "La página de plantilla no puede estar vacía: " + page.getKey());
            }
            Iterator<Map.Entry<String, JsonNode>> fields = page.getValue().properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().isBlank() || !field.getValue().isTextual()) {
                    throw new LocaleCatalogConfigurationException(
                            "La plantilla " + page.getKey()
                                    + " solo puede contener claves no vacías y valores de texto");
                }
            }
        }
        mergePages(templatePages, strictObjectMapper(), TEMPLATE_DIRECTORY);
    }

    /**
     * Valida los datos recibidos mediante {@code validateSpanishLocale}.
     *
     * @param templatePages Páginas que definen el contrato de traducciones.
     * @param spanishPages Páginas con los mensajes en español.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateSpanishLocale(
            Map<String, ObjectNode> templatePages, Map<String, ObjectNode> spanishPages) {
        Set<String> missingPages = difference(templatePages.keySet(), spanishPages.keySet());
        Set<String> unexpectedPages = difference(spanishPages.keySet(), templatePages.keySet());
        if (!missingPages.isEmpty() || !unexpectedPages.isEmpty()) {
            throw new LocaleCatalogConfigurationException(
                    "Las páginas de es no coinciden con template; faltan=" + missingPages
                            + ", sobran=" + unexpectedPages);
        }
        for (Map.Entry<String, ObjectNode> page : templatePages.entrySet()) {
            String pageName = page.getKey();
            ObjectNode spanishPage = spanishPages.get(pageName);
            Set<String> expectedKeys = fieldNames(page.getValue());
            Set<String> actualKeys = fieldNames(spanishPage);
            Set<String> missingKeys = difference(expectedKeys, actualKeys);
            Set<String> unexpectedKeys = difference(actualKeys, expectedKeys);
            if (!missingKeys.isEmpty() || !unexpectedKeys.isEmpty()) {
                throw new LocaleCatalogConfigurationException(
                        "La página es/" + pageName + " no coincide con template/" + pageName
                                + "; faltan=" + missingKeys + ", sobran=" + unexpectedKeys);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = spanishPage.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()) {
                    throw new LocaleCatalogConfigurationException(
                            "La traducción española debe ser texto no vacío para la clave "
                                    + field.getKey() + " en " + pageName);
                }
            }
        }
        mergePages(spanishPages, strictObjectMapper(), SPANISH_DIRECTORY);
    }

    /** Fusiona las páginas, rechazando claves repetidas entre archivos. */
    private ObjectNode mergePages(
            Map<String, ObjectNode> pages, ObjectMapper mapper, String catalogName) {
        ObjectNode merged = mapper.createObjectNode();
        Set<String> duplicated = new HashSet<>();
        for (ObjectNode page : pages.values()) {
            page.properties().forEach(field -> {
                if (merged.has(field.getKey())) {
                    duplicated.add(field.getKey());
                } else {
                    merged.set(field.getKey(), field.getValue());
                }
            });
        }
        if (!duplicated.isEmpty()) {
            throw new LocaleCatalogConfigurationException(
                    "El catálogo " + catalogName + " repite claves entre páginas: " + duplicated);
        }
        return merged;
    }

    /** Serializa el catálogo fusionado que se entrega al cliente. */
    private byte[] writeBytes(ObjectNode content, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsBytes(content);
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo serializar el catálogo de traducciones", exception);
        }
    }

    /**
     * Ejecuta la operación {@code fieldNames}.
     *
     * @param object Valor de {@code object} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private Set<String> fieldNames(ObjectNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Ejecuta la operación {@code difference}.
     *
     * @param left Valor de {@code left} utilizado por la operación.
     * @param right Valor de {@code right} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /**
     * Ejecuta la operación {@code calculateEtag}.
     *
     * @param content Contenido que debe procesarse.
     * @return Resultado producido por {@code calculateEtag}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    private String calculateEtag(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }
}
