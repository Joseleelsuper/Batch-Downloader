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

@RestController
@RequestMapping("/api/v1/locales")
public class LocaleController {

    private static final String SPANISH_LOCALE = "es";
    private static final MediaType UTF_8_JSON = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final GetLocale getLocale;
    private final CacheControl cacheControl;

    public LocaleController(GetLocale getLocale, TranslationProperties properties) {
        this.getLocale = getLocale;
        this.cacheControl = CacheControl.maxAge(properties.cacheMaxAge())
                .cachePublic()
                .mustRevalidate();
    }

    @GetMapping(value = "/es", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> spanish(ServletWebRequest request) {
        return getLocale.execute(SPANISH_LOCALE)
                .map(document -> responseFor(request, document))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> responseFor(
            ServletWebRequest request, LocaleDocument document) {
        if (request.checkNotModified(document.etag())) {
            return ResponseEntity.status(304)
                    .cacheControl(cacheControl)
                    .build();
        }
        return okResponse(document);
    }

    private ResponseEntity<byte[]> okResponse(LocaleDocument document) {
        return ResponseEntity.ok()
                .contentType(UTF_8_JSON)
                .cacheControl(cacheControl)
                .eTag(document.etag())
                .body(document.content());
    }
}
