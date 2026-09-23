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
 * Lee y valida al arrancar las páginas de plantilla y de todos los idiomas publicados y conserva
 * un JSON fusionado por idioma en memoria.
 *
 * Exige paridad de archivos y claves, textos no vacíos y ausencia de claves duplicadas dentro de
 * una página o entre páginas. Calcula un ETag por idioma sobre los bytes que sirve el controlador.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.application.port.LocaleCatalog
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @see es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@Repository
public class JsonFileLocaleCatalog implements LocaleCatalog {

    /**
     * Referencia estable utilizada para construir o validar t e m p l a t e  d i r e c t o r y.
     */
    private static final String TEMPLATE_DIRECTORY = "template";
    /**
     * Catálogos fusionados por idioma, cargados y validados una sola vez durante el arranque.
     */
    private final Map<String, LocaleDocument> cache;

    /**
     * Carga páginas ordenadas, valida la plantilla y fusiona una sola vez el catálogo español que
     * se servirá.
     *
     * @param properties Ruta del catálogo y duración de caché configuradas para el servicio.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     las páginas no existen, no son JSON estricto o incumplen la paridad y unicidad de claves.
     */
    public JsonFileLocaleCatalog(TranslationProperties properties) {
        Path localesPath = properties.localesPath();
        ObjectMapper strictMapper = strictObjectMapper();
        Map<String, ObjectNode> templatePages = readPages(
                localesPath.resolve(TEMPLATE_DIRECTORY), strictMapper);
        validateTemplate(templatePages);
        Map<String, Map<String, ObjectNode>> localePages = readLocalePages(localesPath, strictMapper);
        Map<String, LocaleDocument> documents = new LinkedHashMap<>();
        localePages.forEach((locale, pages) -> {
            ObjectNode merged = validateLocale(templatePages, pages, locale, strictMapper);
            byte[] content = writeBytes(merged, strictMapper);
            documents.put(locale, new LocaleDocument(locale, content, calculateEtag(content)));
        });
        cache = Map.copyOf(documents);
    }

    /**
     * Consulta el documento precargado, sin realizar E/S ni volver a fusionar páginas.
     *
     * @param locale Código exacto del idioma solicitado.
     * @return documento publicado, o Optional vacío para otro código.
     */
    @Override
    public Optional<LocaleDocument> findByLocale(String locale) {
        return Optional.ofNullable(cache.get(locale));
    }

    /**
     * Lee todos los directorios de idiomas publicados, excluyendo la plantilla de referencia.
     *
     * @param localesPath Raíz que contiene template y los directorios de idiomas.
     * @param mapper Lector JSON estricto compartido durante la carga.
     * @return páginas indexadas por código de idioma.
     */
    private Map<String, Map<String, ObjectNode>> readLocalePages(
            Path localesPath, ObjectMapper mapper) {
        if (!Files.isDirectory(localesPath)) {
            throw new LocaleCatalogConfigurationException(
                    "No existe el directorio raíz de traducciones: " + localesPath.getFileName());
        }
        List<Path> directories;
        try (Stream<Path> paths = Files.list(localesPath)) {
            directories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !TEMPLATE_DIRECTORY.equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo listar la raíz de traducciones " + localesPath.getFileName(),
                    exception);
        }
        if (directories.isEmpty()) {
            throw new LocaleCatalogConfigurationException(
                    "No hay directorios de idiomas publicados en " + localesPath.getFileName());
        }
        Map<String, Map<String, ObjectNode>> result = new LinkedHashMap<>();
        for (Path directory : directories) {
            String locale = directory.getFileName().toString();
            result.put(locale, readPages(directory, mapper));
        }
        return result;
    }

    /**
     * Configura Jackson para rechazar propiedades JSON duplicadas al leer cualquier página.
     *
     * @return lector estricto compartido durante la carga del catálogo.
     */
    private ObjectMapper strictObjectMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    /**
     * Lee los archivos JSON regulares de un directorio en orden de nombre y los indexa por página.
     *
     * @param directory Directorio de páginas JSON de un catálogo.
     * @param mapper Lector/serializador JSON configurado para rechazar claves duplicadas.
     * @return mapa ordenado de objetos JSON por nombre de archivo.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     falta el directorio, está vacío, no puede listarse o alguna página no es válida.
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
     * Interpreta una página como un objeto JSON estricto, rechazando otros tipos en la raíz.
     *
     * @param content Bytes UTF-8 del catálogo JSON completo.
     * @param fileName Nombre de la página utilizado en los errores de configuración.
     * @param mapper Lector/serializador JSON configurado para rechazar claves duplicadas.
     * @return objeto raíz de la página.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     el contenido no es JSON estricto o su raíz no es un objeto.
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
     * Lee por completo un archivo regular de traducciones, identificando la página cuando falla.
     *
     * @param path Ruta del archivo regular de traducciones que se debe leer.
     * @return contenido original del archivo.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     la ruta no es un archivo regular o falla su lectura.
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
     * Exige páginas no vacías, claves no vacías y valores de texto; comprueba también duplicados
     * entre páginas.
     *
     * @param templatePages Páginas de referencia con las claves admitidas para cada archivo.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     alguna página o clave de la plantilla incumple esas condiciones.
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
     * Comprueba la paridad de páginas y claves y textos no vacíos; devuelve la fusión validada de
     * un idioma.
     *
     * @param templatePages Páginas de referencia con las claves admitidas para cada archivo.
     * @param localePages Páginas del idioma que deben coincidir con la plantilla.
     * @param locale Código del idioma utilizado en los mensajes de error y en la métrica.
     * @param mapper Lector/serializador JSON configurado para rechazar claves duplicadas.
     * @return catálogo completo del idioma sin claves repetidas.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     faltan o sobran páginas o claves, hay valores inválidos o se repiten claves entre páginas.
     */
    private ObjectNode validateLocale(
            Map<String, ObjectNode> templatePages, Map<String, ObjectNode> localePages,
            String locale, ObjectMapper mapper) {
        Set<String> missingPages = difference(templatePages.keySet(), localePages.keySet());
        Set<String> unexpectedPages = difference(localePages.keySet(), templatePages.keySet());
        if (!missingPages.isEmpty() || !unexpectedPages.isEmpty()) {
            throw new LocaleCatalogConfigurationException("Las páginas de " + locale
                    + " no coinciden con template; faltan=" + missingPages
                    + ", sobran=" + unexpectedPages);
        }
        for (Map.Entry<String, ObjectNode> page : templatePages.entrySet()) {
            String pageName = page.getKey();
            ObjectNode localePage = localePages.get(pageName);
            Set<String> expectedKeys = fieldNames(page.getValue());
            Set<String> actualKeys = fieldNames(localePage);
            Set<String> missingKeys = difference(expectedKeys, actualKeys);
            Set<String> unexpectedKeys = difference(actualKeys, expectedKeys);
            if (!missingKeys.isEmpty() || !unexpectedKeys.isEmpty()) {
                throw new LocaleCatalogConfigurationException(
                        "La página " + locale + "/" + pageName + " no coincide con template/"
                                + pageName
                                + "; faltan=" + missingKeys + ", sobran=" + unexpectedKeys);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = localePage.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual() || field.getValue().textValue().isBlank()) {
                    throw new LocaleCatalogConfigurationException("La traducción de " + locale
                            + " debe ser texto no vacío para la clave " + field.getKey()
                            + " en " + pageName);
                }
            }
        }
        return mergePages(localePages, mapper, locale);
    }

    /**
     * Combina páginas en un objeto plano y rechaza cualquier clave que aparezca en más de una
     * página.
     *
     * @param pages Páginas ya leídas, conservadas por nombre de archivo.
     * @param mapper Lector/serializador JSON configurado para rechazar claves duplicadas.
     * @param catalogName Nombre del catálogo utilizado para identificar claves duplicadas entre
     *     páginas.
     *
     * @return objeto con todas las traducciones, conservando el orden de las páginas.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     alguna clave está repetida entre páginas.
     */
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

    /**
     * Serializa el objeto validado a los bytes JSON que se usarán tanto para el ETag como para
     * HTTP.
     *
     * @param content Objeto JSON fusionado y validado del idioma.
     * @param mapper Lector/serializador JSON configurado para rechazar claves duplicadas.
     * @return representación JSON UTF-8.
     * @throws
     *     es.ubu.batchdownloader.translation.infrastructure.file.LocaleCatalogConfigurationException si
     *     falla la serialización del catálogo.
     */
    private byte[] writeBytes(ObjectNode content, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsBytes(content);
        } catch (IOException exception) {
            throw new LocaleCatalogConfigurationException(
                    "No se pudo serializar el catálogo de traducciones", exception);
        }
    }

    /**
     * Obtiene los nombres de claves para comparar una página española con su plantilla.
     *
     * @param object Objeto JSON cuyos nombres de campo se comparan con la plantilla.
     * @return conjunto independiente de nombres del objeto.
     */
    private Set<String> fieldNames(ObjectNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Calcula qué nombres del primer conjunto no aparecen en el segundo sin modificar los
     * originales.
     *
     * @param left Conjunto de nombres del que se parte, sin modificarlo.
     * @param right Nombres que se excluyen del resultado.
     * @return conjunto de nombres ausentes en right.
     */
    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    /**
     * Calcula SHA-256 sobre los bytes del catálogo y lo encierra entre comillas para usarlo como
     * ETag fuerte.
     *
     * @param content Bytes UTF-8 del catálogo JSON completo.
     * @return ETag estable para exactamente el mismo contenido.
     * @throws IllegalStateException si el runtime no dispone del algoritmo SHA-256.
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
