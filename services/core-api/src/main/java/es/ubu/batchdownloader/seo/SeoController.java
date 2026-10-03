package es.ubu.batchdownloader.seo;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Publica metadatos y HTML del mismo origen con estados HTTP reales y sin caché compartida. */
@RestController
@RequestMapping("/api/v1/seo")
public class SeoController {
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);
    private final SeoService seo;
    private final SeoRepository repository;
    private final SocialCardRenderer images;
    private final CurrentAccount accounts;

    public SeoController(SeoService seo, SeoRepository repository, SocialCardRenderer images, CurrentAccount accounts) {
        this.seo = seo;
        this.repository = repository;
        this.images = images;
        this.accounts = accounts;
    }

    @GetMapping("/metadata")
    public ResponseEntity<SeoService.Metadata> metadata(@RequestParam(defaultValue = "/") String path) {
        SeoService.Resolution resolved = resolve(path);
        return response(resolved.status(), MediaType.APPLICATION_JSON).body(resolved.metadata());
    }

    @GetMapping("/html")
    public ResponseEntity<String> html(
            @RequestParam(required = false) String path,
            @RequestHeader(name = "X-Original-URI", required = false) String originalUri,
            Authentication authentication) {
        String requested = path == null ? originalUri : path;
        SeoService.Resolution resolved = resolve(requested);
        if (resolved.status() == 404 && authentication != null && authentication.isAuthenticated()) {
            var identifier = SeoService.bundleIdentifier(requested);
            if (identifier.isPresent()) {
                try {
                    var viewer = accounts.require(authentication);
                    if (repository.privateBundleAccessible(identifier.get(), viewer.id(), viewer.role() == UserRole.ADMIN)) {
                        resolved = seo.privateShell("/", "Bundle privado");
                    }
                } catch (UnauthorizedException ignored) {
                    // La sesión caducada recibe la misma página genérica que un visitante anónimo.
                } catch (DataAccessException exception) {
                    resolved = seo.unavailable();
                }
            }
        }
        return response(resolved.status(), HTML).varyBy(HttpHeaders.COOKIE)
                .header("X-Robots-Tag", resolved.metadata().robots()).body(seo.html(resolved.metadata()));
    }

    @GetMapping("/image")
    public ResponseEntity<byte[]> image(@RequestParam(defaultValue = "/") String path) {
        SeoService.Resolution resolved = resolve(path);
        if (resolved.status() != 200) return response(resolved.status(), MediaType.IMAGE_PNG).body(new byte[0]);
        try {
            return response(200, MediaType.IMAGE_PNG).body(images.render(resolved.metadata()));
        } catch (IllegalStateException exception) {
            return response(503, MediaType.IMAGE_PNG).body(new byte[0]);
        }
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        return response(200, new MediaType("text", "plain", StandardCharsets.UTF_8)).body(seo.robots());
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemapIndex() {
        try {
            return response(200, XML).body(seo.sitemapIndex());
        } catch (DataAccessException | IllegalStateException exception) {
            return response(503, XML).body("");
        }
    }

    @GetMapping("/sitemap/{group}/{page}.xml")
    public ResponseEntity<String> sitemap(@PathVariable String group, @PathVariable int page) {
        try {
            return seo.sitemap(group, page).map(xml -> response(200, XML).body(xml))
                    .orElseGet(() -> response(404, XML).body(""));
        } catch (DataAccessException exception) {
            return response(503, XML).body("");
        }
    }

    private SeoService.Resolution resolve(String path) {
        try {
            return seo.resolve(path);
        } catch (DataAccessException exception) {
            return seo.unavailable();
        }
    }

    private static ResponseEntity.BodyBuilder response(int status, MediaType type) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(type)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff");
        if (status == 503) response.header(HttpHeaders.RETRY_AFTER, "30");
        return response;
    }
}
