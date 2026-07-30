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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone las operaciones HTTP gestionadas por {@code LocaleController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
@RequestMapping("/api/v1/locales")
public class LocaleController {

    /**
     * Constante que define {@code SPANISH_LOCALE}.
     */
    private static final String SPANISH_LOCALE = "es";
    /**
     * Constante que define {@code UTF_8_JSON}.
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
     * Inicializa una instancia de {@code LocaleController}.
     *
     * @param getLocale Valor de {@code getLocale} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public LocaleController(GetLocale getLocale, TranslationProperties properties) {
        this.getLocale = getLocale;
        this.cacheControl = CacheControl.maxAge(properties.cacheMaxAge())
                .cachePublic()
                .mustRevalidate();
    }

    /**
     * Ejecuta la operación {@code spanish}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code spanish}.
     */
    @GetMapping(value = "/es", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spanish(ServletWebRequest request) {
        return getLocale.execute(SPANISH_LOCALE)
                .map(document -> responseFor(request, document))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Ejecuta la operación {@code responseFor}.
     *
     * @param request Solicitud recibida por la operación.
     * @param document Valor de {@code document} utilizado por la operación.
     * @return Resultado producido por {@code responseFor}.
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
     * Ejecuta la operación {@code okResponse}.
     *
     * @param document Valor de {@code document} utilizado por la operación.
     * @return Resultado producido por {@code okResponse}.
     */
    private ResponseEntity<byte[]> okResponse(LocaleDocument document) {
        return ResponseEntity.ok()
                .contentType(UTF_8_JSON)
                .cacheControl(cacheControl)
                .eTag(document.etag())
                .body(document.content());
    }
}
