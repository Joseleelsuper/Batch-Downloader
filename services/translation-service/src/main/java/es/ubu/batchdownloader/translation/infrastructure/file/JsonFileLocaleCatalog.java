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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * Constante que define {@code TEMPLATE_FILE}.
     */
    private static final String TEMPLATE_FILE = "template.json";
    /**
     * Constante que define {@code SPANISH_FILE}.
     */
    private static final String SPANISH_FILE = "es.json";

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
        ObjectNode template = readObject(localesPath.resolve(TEMPLATE_FILE), strictMapper);
        byte[] spanishContent = readBytes(localesPath.resolve(SPANISH_FILE));
        ObjectNode spanish = readObject(spanishContent, SPANISH_FILE, strictMapper);
        validateTemplate(template);
        validateSpanishLocale(template, spanish);
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
     * Ejecuta la operación {@code readObject}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @param mapper Valor de {@code mapper} utilizado por la operación.
     * @return Resultado producido por {@code readObject}.
     */
    private ObjectNode readObject(Path path, ObjectMapper mapper) {
        return readObject(readBytes(path), path.getFileName().toString(), mapper);
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
     * @param template Valor de {@code template} utilizado por la operación.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateTemplate(ObjectNode template) {
        if (template.isEmpty()) {
            throw new LocaleCatalogConfigurationException("template.json no puede estar vacío");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().isBlank() || !field.getValue().isTextual()) {
                throw new LocaleCatalogConfigurationException(
                        "La plantilla solo puede contener claves no vacías y valores de texto");
            }
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateSpanishLocale}.
     *
     * @param template Valor de {@code template} utilizado por la operación.
     * @param spanish Valor de {@code spanish} utilizado por la operación.
     * @throws LocaleCatalogConfigurationException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateSpanishLocale(ObjectNode template, ObjectNode spanish) {
        Set<String> expectedKeys = fieldNames(template);
        Set<String> actualKeys = fieldNames(spanish);
        Set<String> missingKeys = difference(expectedKeys, actualKeys);
        Set<String> unexpectedKeys = difference(actualKeys, expectedKeys);
        if (!missingKeys.isEmpty() || !unexpectedKeys.isEmpty()) {
            throw new LocaleCatalogConfigurationException(
                    "es.json no coincide con template.json; faltan=" + missingKeys
                            + ", sobran=" + unexpectedKeys);
        }

        Iterator<Map.Entry<String, JsonNode>> fields = spanish.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()) {
                throw new LocaleCatalogConfigurationException(
                        "La traducción española debe ser texto no vacío para la clave " + field.getKey());
            }
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
