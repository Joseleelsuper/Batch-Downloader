package es.ubu.batchdownloader.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.UuidBytes;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SeoControllerTest {
    private static final UUID APP_ID = UUID.fromString("71f62b21-3e83-4110-8cd1-8766a94474c0");
    private static final UUID BUNDLE_ID = UUID.fromString("81f62b21-3e83-4110-8cd1-8766a94474c0");
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private SeoService service;
    private CurrentAccount accounts;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:seo-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE software_apps(id BINARY(16) PRIMARY KEY, slug VARCHAR(180), winstall_id VARCHAR(180),
                    name VARCHAR(180), description TEXT, publisher VARCHAR(180), app_status VARCHAR(16), updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE bundles(id BINARY(16) PRIMARY KEY, slug VARCHAR(180), name VARCHAR(180), description TEXT,
                    visibility VARCHAR(16), owner_id CHAR(36), updated_at TIMESTAMP)
                """);
        insertApp(APP_ID, "editor", "Editor de código", "Un editor para trabajar con proyectos.", "active");
        jdbc.update("INSERT INTO bundles VALUES(?, 'tools', 'Herramientas de trabajo', 'Editores y utilidades.', 'public', NULL, CURRENT_TIMESTAMP)",
                UuidBytes.fromUuid(BUNDLE_ID));
        SeoRepository repository = new SeoRepository(jdbc);
        service = new SeoService(repository, new ObjectMapper(), "https://batchdownloader.dev");
        accounts = mock(CurrentAccount.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SeoController(service, repository, new SocialCardRenderer(), accounts)).build();
    }

    @AfterEach
    void closeDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void initialHtmlContainsDynamicMetadataCrawlableSummaryAndOneFrontendInclude() throws Exception {
        String html = mvc.perform(get("/api/v1/seo/html")
                        .header("X-Original-URI", "/catalog/app/editor"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("<h1>Editor de código | Batch Downloader</h1>",
                "property=\"og:title\" content=\"Editor de código | Batch Downloader\"",
                "name=\"twitter:card\" content=\"summary_large_image\"",
                "https://batchdownloader.dev/catalog/app/" + APP_ID,
                "\"@type\":\"SoftwareApplication\"",
                "Un editor para trabajar con proyectos.");
        assertThat(html.split("<!--#", -1)).hasSize(2);
        assertThat(html).contains("<!--# include virtual=\"/__frontend_head\" -->");
        mvc.perform(get("/api/v1/seo/metadata").param("path", "/catalog/app/Editor.Package"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canonicalUrl")
                        .value("https://batchdownloader.dev/catalog/app/" + APP_ID));
    }

    @Test
    void catalogTextCannotBreakHtmlJsonOrInjectSsi() throws Exception {
        String payload = "</script><script>alert(1)</script><!--# include virtual=\"/private\" --> & \"quoted\"";
        jdbc.update("UPDATE software_apps SET name=?, description=? WHERE id=?", payload, payload, UuidBytes.fromUuid(APP_ID));
        String html = mvc.perform(get("/api/v1/seo/html").param("path", "/catalog/app/editor"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<script>alert(1)", "<!--# include virtual=\"/private\"")
                .contains("&lt;/script&gt;", "\\u003c/script\\u003e", "&quot;quoted&quot;");
        assertThat(html.split("<!--#", -1)).hasSize(2);
        String json = html.substring(html.indexOf("id=\"page-structured-data\">") + "id=\"page-structured-data\">".length());
        json = json.substring(0, json.indexOf("</script>"));
        assertThat(new ObjectMapper().readTree(json).get("name").asText()).isEqualTo(payload);
    }

    @Test
    void noindexRoutesAndQueryVariantsNeverEchoTokensOrTrustTheHostHeader() throws Exception {
        for (String path : List.of("/catalog?searchMode=semantic", "/catalog?searchMode=lexical&status=available&sort=downloads&page=1&pageSize=12",
                "/catalog/app/editor?query=other&sort=updated&searchMode=semantic")) {
            mvc.perform(get("/api/v1/seo/metadata").param("path", path))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.robots").value("index, follow"));
        }
        mvc.perform(get("/api/v1/seo/metadata").param("path", "/catalog?query=personal-search&sort=name")
                        .header("Host", "attacker.example"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.robots").value("noindex, follow"))
                .andExpect(jsonPath("$.canonicalUrl").value("https://batchdownloader.dev/catalog"));
        String html = mvc.perform(get("/api/v1/seo/html").header("X-Original-URI", "/login?token=secret-token"))
                .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag", "noindex, follow"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("secret-token", "token=");
        for (String path : List.of("/missing-page", "https://attacker.example/catalog", "//attacker.example/catalog", "/catalog/%0A")) {
            mvc.perform(get("/api/v1/seo/metadata").param("path", path))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.robots").value("noindex, nofollow"));
        }
    }

    @Test
    void trailingSlashNormalizationPreservesCanonicalPathsAndRejectsAmbiguousSeparators() {
        SeoService withTrailingSlash = new SeoService(new SeoRepository(jdbc), new ObjectMapper(), "https://batchdownloader.dev/");
        assertThat(withTrailingSlash.resolve("/").metadata().canonicalUrl()).isEqualTo("https://batchdownloader.dev/");
        assertThat(withTrailingSlash.resolve("/catalog/").metadata().canonicalUrl()).isEqualTo("https://batchdownloader.dev/catalog");
        assertThat(withTrailingSlash.resolve("/catalog/app/editor/?ignored=true").metadata().canonicalUrl())
                .isEqualTo("https://batchdownloader.dev/catalog/app/" + APP_ID);
        for (String path : List.of("/catalog//", "/catalog/%2F", "/catalog/" + "/".repeat(4_000), "/catalog/%5C")) {
            assertThat(withTrailingSlash.resolve(path).status()).as(path).isEqualTo(404);
        }
    }

    @Test
    void catalogDefaultsRemainIndexableWhileExplicitFiltersAreNoindex() {
        for (String query : List.of("status=available", "sort=downloads", "page=1", "pageSize=12",
                "os=windows&os=linux&os=macos", "query=&tag=%20&publisher&architecture=", "unknown=value")) {
            assertThat(service.resolve("/catalog?" + query).metadata().robots()).as(query).isEqualTo("index, follow");
        }
        for (String query : List.of("status=review", "sort=relevance", "page=2", "pageSize=20",
                "os=linux", "os=windows&os=linux", "query=editor", "tag=tools", "publisher=editor", "architecture=arm64")) {
            assertThat(service.resolve("/catalog?" + query).metadata().robots()).as(query).isEqualTo("noindex, follow");
        }
    }

    @Test
    void publicationChangesImmediatelyRemoveMetadataImagesAndSitemapWhileOwnerKeepsGenericShell() throws Exception {
        String path = "/bundles/tools";
        mvc.perform(get("/api/v1/seo/metadata").param("path", path))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Herramientas de trabajo | Batch Downloader"));
        mvc.perform(get("/api/v1/seo/sitemap/bundles/1.xml")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(BUNDLE_ID.toString())));

        UserAccount owner = UserAccount.createUser("owner", "owner", "owner@example.test", "owner@example.test", Instant.EPOCH);
        jdbc.update("UPDATE bundles SET visibility='private', owner_id=? WHERE id=?", owner.id().toString(), UuidBytes.fromUuid(BUNDLE_ID));
        var auth = UsernamePasswordAuthenticationToken.authenticated(AccountPrincipal.from(owner), null, List.of());
        when(accounts.require(any())).thenReturn(owner);
        mvc.perform(get("/api/v1/seo/metadata").param("path", path).principal(auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Página no encontrada | Batch Downloader"));
        mvc.perform(get("/api/v1/seo/image").param("path", path).principal(auth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/seo/sitemap/bundles/1.xml")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/seo/html").param("path", path)).andExpect(status().isNotFound());
        String ownerHtml = mvc.perform(get("/api/v1/seo/html").param("path", path).principal(auth))
                .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag", "noindex, follow"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ownerHtml).contains("Bundle privado").doesNotContain("Herramientas de trabajo", "Editores y utilidades.", owner.email());

        UserAccount other = UserAccount.createUser("other", "other", "other@example.test", "other@example.test", Instant.EPOCH);
        when(accounts.require(any())).thenReturn(other);
        mvc.perform(get("/api/v1/seo/html").param("path", path).principal(auth)).andExpect(status().isNotFound());
        UserAccount admin = UserAccount.bootstrapAdmin("admin", "admin", "admin@example.test", "admin@example.test", "hash", Instant.EPOCH);
        when(accounts.require(any())).thenReturn(admin);
        mvc.perform(get("/api/v1/seo/html").param("path", path).principal(auth))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Herramientas de trabajo"))));
    }

    @Test
    void sitemapIsBoundedCanonicalAndExcludesInactiveAppsAndPrivateBundles() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            rows.add(new Object[]{UuidBytes.fromUuid(UUID.randomUUID()), "app-" + index, "Package." + index, "App " + index});
        }
        jdbc.batchUpdate("INSERT INTO software_apps VALUES(?, ?, ?, ?, 'Description', 'Publisher', 'active', CURRENT_TIMESTAMP)", rows);
        UUID inactive = UUID.randomUUID();
        insertApp(inactive, "hidden", "Invisible", "Never public", "inactive");
        jdbc.update("UPDATE bundles SET visibility='private'");
        String index = mvc.perform(get("/api/v1/seo/sitemap.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(index).contains("/sitemap/apps/1.xml", "/sitemap/apps/2.xml", "/sitemap/static/1.xml")
                .doesNotContain("/sitemap/bundles/");
        String first = mvc.perform(get("/api/v1/seo/sitemap/apps/1.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/api/v1/seo/sitemap/apps/2.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(first.split("<url>", -1)).hasSize(1_001);
        assertThat(second.split("<url>", -1)).hasSize(2);
        assertThat(first + second).doesNotContain(inactive.toString(), "/catalog/app/editor");
        mvc.perform(get("/api/v1/seo/sitemap/apps/3.xml")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/seo/sitemap/bundles/0.xml")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/seo/sitemap/private/1.xml")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/seo/metadata").param("path", "/catalog/app/hidden")).andExpect(status().isNotFound());
        assertThat(service.robots()).contains("Sitemap: https://batchdownloader.dev/sitemap.xml", "Disallow: /api/");
    }

    @Test
    void socialImagesAreRealPngsWithPageSpecificContentAndFixedDimensions() throws Exception {
        byte[] app = mvc.perform(get("/api/v1/seo/image").param("path", "/catalog/app/editor"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] home = mvc.perform(get("/api/v1/seo/image").param("path", "/"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        byte[] bundle = mvc.perform(get("/api/v1/seo/image").param("path", "/bundles/tools"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        byte[] privacy = mvc.perform(get("/api/v1/seo/image").param("path", "/privacy"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var decoded = ImageIO.read(new ByteArrayInputStream(app));
        for (byte[] png : List.of(app, home, bundle, privacy)) {
            var card = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(card.getWidth()).isEqualTo(1_200);
            assertThat(card.getHeight()).isEqualTo(630);
        }
        var bundleCard = ImageIO.read(new ByteArrayInputStream(bundle));
        // La ilustración superior distingue una aplicación de un grupo, incluso sin leer el título.
        assertThat(decoded.getRGB(920, 20, 230, 140, null, 0, 230))
                .isNotEqualTo(bundleCard.getRGB(920, 20, 230, 140, null, 0, 230));
        var homeCard = ImageIO.read(new ByteArrayInputStream(home));
        var privacyCard = ImageIO.read(new ByteArrayInputStream(privacy));
        assertThat(homeCard.getRGB(70, 550, 600, 50, null, 0, 600))
                .isNotEqualTo(privacyCard.getRGB(70, 550, 600, 50, null, 0, 600));
        assertThat(app).isNotEqualTo(home);
    }

    @Test
    void longUnbrokenAndMultilineCardTextCannotOverlapTheFooter() throws Exception {
        SocialCardRenderer renderer = new SocialCardRenderer();
        var original = ImageIO.read(new ByteArrayInputStream(renderer.render(service.resolve("/catalog/app/editor").metadata())));
        for (String title : List.of("W".repeat(170), "Texto largo ".repeat(14))) {
            jdbc.update("UPDATE software_apps SET name=?, description=? WHERE id=?", title, title.repeat(3), UuidBytes.fromUuid(APP_ID));
            var card = ImageIO.read(new ByteArrayInputStream(renderer.render(service.resolve("/catalog/app/editor").metadata())));
            assertThat(card.getRGB(70, 180, 1_060, 350, null, 0, 1_060))
                    .isNotEqualTo(original.getRGB(70, 180, 1_060, 350, null, 0, 1_060));
            assertThat(card.getRGB(70, 550, 1_060, 70, null, 0, 1_060))
                    .isEqualTo(original.getRGB(70, 550, 1_060, 70, null, 0, 1_060));
        }
    }

    @Test
    void databaseFailureReturnsReal503WithSafeHtmlAndMetadata() throws Exception {
        jdbc.execute("DROP TABLE software_apps");
        mvc.perform(get("/api/v1/seo/metadata").param("path", "/catalog/app/editor"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.robots").value("noindex, nofollow"));
        String html = mvc.perform(get("/api/v1/seo/html").param("path", "/catalog/app/editor"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("temporalmente no disponible").doesNotContain("software_apps", "SELECT", "jdbc:h2");
        mvc.perform(get("/api/v1/seo/sitemap.xml")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/seo/image").param("path", "/catalog/app/editor")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/seo/html").param("path", "/")).andExpect(status().isOk());
    }

    private void insertApp(UUID id, String slug, String name, String description, String state) {
        jdbc.update("INSERT INTO software_apps VALUES(?, ?, 'Editor.Package', ?, ?, 'Editor publisher', ?, CURRENT_TIMESTAMP)",
                UuidBytes.fromUuid(id), slug, name, description, state);
    }
}
