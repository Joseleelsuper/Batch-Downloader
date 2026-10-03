package es.ubu.batchdownloader.seo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Resuelve la misma información para HTML inicial, navegación React y tarjetas sociales. */
@Service
public class SeoService {
    static final String BRAND = "Batch Downloader";
    private static final String INDEX = "index, follow";
    private static final String NOINDEX = "noindex, follow";
    private static final Map<String, List<String>> PUBLIC_PAGES = Map.of(
            "/", List.of("Descarga varias aplicaciones a la vez", "Busca aplicaciones para Windows, Linux y macOS, crea bundles y descarga sus instaladores juntos con Batch Downloader."),
            "/catalog", List.of("Catálogo de aplicaciones", "Encuentra aplicaciones para Windows, Linux y macOS por nombre, editor, etiquetas o búsqueda semántica. Prepara tus descargas en un solo lugar."),
            "/catalog/tags", List.of("Etiquetas del catálogo", "Explora las etiquetas de Batch Downloader y encuentra aplicaciones por su función, categoría y uso."),
            "/catalog/editors", List.of("Editores de aplicaciones", "Descubre los editores del catálogo de Batch Downloader y consulta sus aplicaciones y fuentes disponibles."),
            "/terms", List.of("Términos y condiciones", "Consulta las condiciones de uso de Batch Downloader, los bundles y las descargas de aplicaciones de terceros."),
            "/privacy", List.of("Política de privacidad", "Conoce cómo Batch Downloader trata los datos de cuentas, descargas, cookies y almacenamiento del navegador, y cómo ejercer tus derechos."));
    private static final Map<String, String> PRIVATE_PAGES = Map.ofEntries(
            Map.entry("/login", "Accede a tu cuenta"), Map.entry("/admin/login", "Acceso de administración"),
            Map.entry("/dashboard", "Mi panel"), Map.entry("/profile", "Mi perfil"),
            Map.entry("/dashboard/bundles", "Mis bundles"), Map.entry("/dashboard/bundles/new", "Crear bundle"),
            Map.entry("/admin", "Administración"), Map.entry("/admin/apps", "Administrar aplicaciones"),
            Map.entry("/admin/bundles", "Administrar bundles"), Map.entry("/admin/scraper", "Administrar scraper"),
            Map.entry("/admin/semantic", "Administrar búsqueda semántica"), Map.entry("/admin/audit", "Auditoría"),
            Map.entry("/error", "No se pudo completar la solicitud"));
    private final SeoRepository repository;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public SeoService(SeoRepository repository, ObjectMapper mapper,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.repository = repository;
        this.mapper = mapper;
        URI configured = URI.create(publicBaseUrl);
        if (!("http".equals(configured.getScheme()) || "https".equals(configured.getScheme()))
                || configured.getHost() == null || configured.getRawUserInfo() != null
                || configured.getRawQuery() != null || configured.getRawFragment() != null
                || !(configured.getPath().isEmpty() || "/".equals(configured.getPath()))) {
            throw new IllegalArgumentException("invalid_public_base_url");
        }
        baseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public record Metadata(String title, String description, String canonicalUrl, String imageUrl,
            String imageAlt, String robots, String type, Map<String, Object> structuredData) {}
    public record Resolution(int status, Metadata metadata) {}

    public Resolution resolve(String requestedPath) {
        String path = path(requestedPath);
        List<String> staticPage = PUBLIC_PAGES.get(path);
        boolean filtered = filteredCatalog(requestedPath, path);
        if (staticPage != null) {
            return page(path, staticPage.getFirst(), staticPage.get(1), filtered ? NOINDEX : INDEX,
                    "/".equals(path) ? "WebSite" : "WebPage", null);
        }
        String privateTitle = PRIVATE_PAGES.get(path);
        if (privateTitle == null && (path.matches("/dashboard/bundles/[^/]+/edit")
                || path.matches("/admin/semantic/[^/]+"))) privateTitle = "Área personal";
        if (privateTitle != null) return privateShell(path, privateTitle);
        if (path.startsWith("/catalog/app/") && oneSegment(path, "/catalog/app/")) {
            return repository.app(path.substring("/catalog/app/".length()))
                    .map(app -> page("/catalog/app/" + app.id(), app.name(),
                            fallback(app.description(), "Consulta " + app.name() + " y sus opciones de descarga en Batch Downloader."),
                            INDEX, "SoftwareApplication", app.publisher()))
                    .orElseGet(this::notFound);
        }
        if (path.startsWith("/bundles/") && oneSegment(path, "/bundles/")) {
            return repository.bundle(path.substring("/bundles/".length()))
                    .map(bundle -> page("/bundles/" + bundle.id(), bundle.name(),
                            fallback(bundle.description(), "Explora las aplicaciones del bundle " + bundle.name() + " y descarga sus instaladores juntos."),
                            INDEX, "CollectionPage", null))
                    .orElseGet(this::notFound);
        }
        return notFound();
    }

    public Resolution privateShell(String path, String title) {
        return page(path, title, "Accede a Batch Downloader para gestionar tu cuenta, tus bundles y tus descargas.",
                NOINDEX, "WebPage", null);
    }

    public Resolution notFound() {
        return error(404, "Página no encontrada", "Esta página no está disponible. Explora el catálogo público de Batch Downloader.");
    }

    public Resolution unavailable() {
        return error(503, "Servicio temporalmente no disponible", "No se pudo consultar el catálogo. Vuelve a intentarlo en unos momentos.");
    }

    private Resolution error(int status, String title, String description) {
        Metadata safe = page("/", title, description, "noindex, nofollow", "WebPage", null).metadata();
        return new Resolution(status, safe);
    }

    private Resolution page(String path, String title, String description, String robots,
            String schemaType, String publisher) {
        String name = text(title, 180);
        String summary = text(description, 260);
        String canonical = baseUrl + path;
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("@context", "https://schema.org");
        structured.put("@type", schemaType);
        structured.put("name", name);
        structured.put("description", summary);
        structured.put("url", canonical);
        structured.put("inLanguage", "es");
        if (publisher != null && !publisher.isBlank()) {
            structured.put("publisher", Map.of("@type", "Organization", "name", text(publisher, 180)));
        }
        return new Resolution(200, new Metadata(name + " | " + BRAND, summary, canonical,
                baseUrl + "/social/card.png?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8),
                name + " — " + BRAND, robots, "website", Map.copyOf(structured)));
    }

    /** No acepta URLs externas, rutas ambiguas ni consultas que puedan filtrarse al documento. */
    static String path(String value) {
        if (value == null || value.isBlank()) return "/";
        if (value.length() > 4_096 || value.indexOf('\\') >= 0 || value.chars().anyMatch(c -> c < 32)) return "";
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) return "";
            String path = uri.getPath();
            if (path == null || !path.startsWith("/") || path.contains("//")
                    || path.chars().anyMatch(c -> c < 32 || c == '\\')) return "";
            return path.length() > 1 ? path.replaceAll("/+$", "") : path;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    static Optional<String> bundleIdentifier(String requestedPath) {
        String path = path(requestedPath);
        return path.startsWith("/bundles/") && oneSegment(path, "/bundles/")
                ? Optional.of(path.substring("/bundles/".length())) : Optional.empty();
    }

    private static boolean oneSegment(String path, String prefix) {
        return path.length() > prefix.length() && path.indexOf('/', prefix.length()) < 0;
    }

    /** El modo de búsqueda y los filtros por defecto añadidos por React no ocultan el catálogo. */
    private static boolean filteredCatalog(String requested, String path) {
        if (requested == null || !List.of("/catalog", "/catalog/tags", "/catalog/editors").contains(path)) return false;
        String query = URI.create(requested).getRawQuery();
        if (query == null || query.isBlank()) return false;
        Set<String> systems = new java.util.HashSet<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8).strip() : "";
            if (value.isEmpty()) continue;
            if (Set.of("query", "tag", "publisher", "architecture").contains(key)) return true;
            if (("status".equals(key) && !"available".equals(value))
                    || ("sort".equals(key) && !"downloads".equals(value))
                    || ("page".equals(key) && !"1".equals(value))
                    || ("pageSize".equals(key) && !"12".equals(value))) return true;
            if ("os".equals(key)) systems.add(value);
        }
        return !systems.isEmpty() && !systems.equals(Set.of("windows", "linux", "macos"));
    }

    private static String fallback(String value, String alternative) {
        return value == null || value.isBlank() ? alternative : value;
    }

    static String text(String value, int length) {
        String cleaned = value.replaceAll("\\s+", " ").strip();
        int count = cleaned.codePointCount(0, cleaned.length());
        return count > length ? cleaned.substring(0, cleaned.offsetByCodePoints(0, length - 1)) + "…" : cleaned;
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Las secuencias HTML y SSI del catálogo nunca se interpretan como marcado o instrucciones. */
    public String html(Metadata metadata) {
        String json;
        try {
            json = mapper.writeValueAsString(metadata.structuredData())
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("seo_serialization_failed", exception);
        }
        return """
                <!doctype html>
                <html lang="es"><head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <meta name="description" content="%s"><meta name="robots" content="%s">
                <link rel="canonical" href="%s">
                <meta property="og:type" content="%s"><meta property="og:site_name" content="Batch Downloader">
                <meta property="og:locale" content="es_ES"><meta property="og:title" content="%s">
                <meta property="og:description" content="%s"><meta property="og:url" content="%s">
                <meta property="og:image" content="%s"><meta property="og:image:alt" content="%s">
                <meta property="og:image:type" content="image/png"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
                <meta name="twitter:card" content="summary_large_image"><meta name="twitter:title" content="%s">
                <meta name="twitter:description" content="%s"><meta name="twitter:image" content="%s"><meta name="twitter:image:alt" content="%s">
                <script type="application/ld+json" id="page-structured-data">%s</script>
                <!--# include virtual="/__frontend_head" -->
                </head><body><div id="root"><main class="content-page">
                <h1>%s</h1><p>%s</p><nav aria-label="Páginas públicas">
                <a href="/">Inicio</a> · <a href="/catalog">Catálogo</a> · <a href="/catalog/tags">Etiquetas</a> · <a href="/catalog/editors">Editores</a> · <a href="/terms">Condiciones</a> · <a href="/privacy">Privacidad</a>
                </nav></main></div></body></html>
                """.formatted(escape(metadata.title()), escape(metadata.description()), escape(metadata.robots()),
                escape(metadata.canonicalUrl()), escape(metadata.type()), escape(metadata.title()),
                escape(metadata.description()), escape(metadata.canonicalUrl()), escape(metadata.imageUrl()),
                escape(metadata.imageAlt()), escape(metadata.title()), escape(metadata.description()),
                escape(metadata.imageUrl()), escape(metadata.imageAlt()), json,
                escape(metadata.title()), escape(metadata.description()));
    }

    public String robots() {
        return "User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /admin\nDisallow: /dashboard\n"
                + "Disallow: /profile\nDisallow: /login\nDisallow: /error\nSitemap: " + baseUrl + "/sitemap.xml\n";
    }

    public String sitemapIndex() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        xml.append("<sitemap><loc>").append(escape(baseUrl)).append("/sitemap/static/1.xml</loc></sitemap>");
        for (String group : List.of("apps", "bundles")) {
            long pages = (repository.sitemapCount(group) + SeoRepository.SITEMAP_PAGE_SIZE - 1) / SeoRepository.SITEMAP_PAGE_SIZE;
            if (pages > 24_999) throw new IllegalStateException("sitemap_capacity_exceeded");
            for (int page = 1; page <= pages; page++) {
                xml.append("<sitemap><loc>").append(escape(baseUrl)).append("/sitemap/")
                        .append(group).append('/').append(page).append(".xml</loc></sitemap>");
            }
        }
        return xml.append("</sitemapindex>").toString();
    }

    public Optional<String> sitemap(String group, int page) {
        if (page < 1 || !List.of("static", "apps", "bundles").contains(group)) return Optional.empty();
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        if ("static".equals(group)) {
            if (page != 1) return Optional.empty();
            PUBLIC_PAGES.keySet().stream().sorted().forEach(path -> xml.append("<url><loc>")
                    .append(escape(baseUrl + path)).append("</loc></url>"));
        } else {
            long count = repository.sitemapCount(group);
            if ((long) (page - 1) * SeoRepository.SITEMAP_PAGE_SIZE >= count) return Optional.empty();
            String prefix = "apps".equals(group) ? "/catalog/app/" : "/bundles/";
            for (SeoRepository.SitemapEntry entry : repository.sitemapEntries(group, page)) {
                xml.append("<url><loc>").append(escape(baseUrl + prefix + entry.id()))
                        .append("</loc><lastmod>").append(entry.updatedAt().toLocalDate())
                        .append("</lastmod></url>");
            }
        }
        return Optional.of(xml.append("</urlset>").toString());
    }
}
