package es.ubu.batchdownloader.translation.domain;

import java.util.Objects;

/**
 * Representa los datos inmutables de {@code LocaleDocument}.
 *
 * @param locale Valor de {@code locale} incluido en el record.
 * @param content Valor de {@code content} incluido en el record.
 * @param etag Valor de {@code etag} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record LocaleDocument(String locale, byte[] content, String etag) {

    /**
     * Inicializa una instancia de {@code LocaleDocument}.
     *
     * @param locale Valor de {@code locale} utilizado por la operación.
     * @param content Contenido que debe procesarse.
     * @param etag Valor de {@code etag} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
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
     * Implementa {@code content} para {@code LocaleDocument}.
     *
     * @return Resultado producido por {@code content}.
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
