package es.ubu.batchdownloader.translation.infrastructure.web;

import es.ubu.batchdownloader.translation.application.GetLocale;
import es.ubu.batchdownloader.translation.config.TranslationProperties;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publica cualquier catálogo publicado como JSON UTF-8 y permite revalidarlo mediante su ETag.
 * Devuelve 200 con contenido o 304 cuando el navegador ya conserva esa representación,
 * aplicando la duración de caché pública configurada y revalidación obligatoria.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.application.GetLocale
 * @see es.ubu.batchdownloader.translation.domain.LocaleDocument
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@RestController
@RequestMapping("/api/v1/locales")
public class LocaleController {

    /**
     * Valor compartido que fija u t f 8  j s o n para el comportamiento del componente.
     */
    private static final MediaType UTF_8_JSON = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /**
     * Estado {@code getLocale} mantenido por {@code LocaleController}.
     */
    private final GetLocale getLocale;
    /**
     * Estado {@code cacheControl} mantenido por {@code LocaleController}.
     */
    private final CacheControl cacheControl;

    /**
     * Asocia la consulta del catálogo con la política de caché pública y revalidación de sus
     * respuestas.
     *
     * @param getLocale Consulta del documento validado por código de idioma.
     * @param properties Ruta del catálogo y duración de caché configuradas para el servicio.
     */
    public LocaleController(GetLocale getLocale, TranslationProperties properties) {
        this.getLocale = getLocale;
        this.cacheControl = CacheControl.maxAge(properties.cacheMaxAge())
                .cachePublic()
                .mustRevalidate();
    }

    /**
     * Consulta el catálogo solicitado y aplica la petición condicional del navegador.
     *
     * @param request Petición HTTP que contiene los validadores condicionales del navegador.
     * @return 200 con JSON, 304 si no cambió o 404 si el idioma no está disponible.
     */
    @GetMapping(value = "/{locale}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> locale(
            @PathVariable String locale, ServletWebRequest request) {
        return getLocale.execute(locale)
                .map(document -> responseFor(request, document))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Compara los validadores de la petición con el ETag del documento antes de decidir si envía el
     * cuerpo.
     *
     * @param request Petición HTTP que contiene los validadores condicionales del navegador.
     * @param document Catálogo JSON validado con idioma, bytes y ETag.
     * @return 304 sin cuerpo para contenido vigente, o 200 con el catálogo completo.
     */
    private ResponseEntity<byte[]> responseFor(
            ServletWebRequest request, LocaleDocument document) {
        if (request.checkNotModified(document.etag())) {
            return ResponseEntity.status(304)
                    .cacheControl(cacheControl)
                    .build();
        }
        return okResponse(document);
    }

    /**
     * Construye la respuesta JSON UTF-8 con su ETag, política de caché y copia del contenido.
     *
     * @param document Catálogo JSON validado con idioma, bytes y ETag.
     * @return respuesta 200 del catálogo disponible.
     */
    private ResponseEntity<byte[]> okResponse(LocaleDocument document) {
        return ResponseEntity.ok()
                .contentType(UTF_8_JSON)
                .cacheControl(cacheControl)
                .eTag(document.etag())
                .body(document.content());
    }
}
