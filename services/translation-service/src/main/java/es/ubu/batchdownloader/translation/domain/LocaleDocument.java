package es.ubu.batchdownloader.translation.domain;

import java.util.Objects;

public record LocaleDocument(String locale, byte[] content, String etag) {

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

    @Override
    public byte[] content() {
        return content.clone();
    }
}
