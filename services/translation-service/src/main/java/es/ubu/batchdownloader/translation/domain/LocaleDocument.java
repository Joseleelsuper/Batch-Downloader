package es.ubu.batchdownloader.translation.domain;

import java.util.Objects;

/**
 * Conserva una representación JSON de un idioma con el ETag que permite revalidarla por HTTP.
 * Copia el array de entrada y el devuelto por content para impedir cambios externos del documento.
 *
 * @param locale Código exacto del idioma solicitado; el catálogo actual publica es.
 * @param content Bytes UTF-8 del catálogo JSON completo.
 * @param etag Validador HTTP del contenido, con comillas y basado en su SHA-256.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.application.port.LocaleCatalog
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
public record LocaleDocument(String locale, byte[] content, String etag) {

    /**
     * Exige código de idioma y ETag no vacíos y conserva una copia independiente de los bytes.
     *
     * @param locale Código exacto del idioma solicitado; el catálogo actual publica es.
     * @param content Bytes UTF-8 del catálogo JSON completo.
     * @param etag Validador HTTP del contenido, con comillas y basado en su SHA-256.
     * @throws IllegalArgumentException si idioma o ETag están vacíos.
     * @throws NullPointerException si falta el contenido.
     */
    public LocaleDocument {
        if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("locale no puede estar vacío");
        }
        locale = locale.strip();
        content = Objects.requireNonNull(content, "content no puede ser null").clone();
        if (etag == null || etag.isBlank()) {
            throw new IllegalArgumentException("etag no puede estar vacío");
        }
    }

    /**
     * Devuelve una copia del JSON para que el llamador no modifique el documento almacenado.
     *
     * @return copia independiente de los bytes UTF-8.
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
