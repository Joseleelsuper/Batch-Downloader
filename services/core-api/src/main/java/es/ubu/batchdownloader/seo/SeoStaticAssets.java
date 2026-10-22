package es.ubu.batchdownloader.seo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Recursos SEO editables y generación de las tarjetas fijas durante la compilación. */
public final class SeoStaticAssets {
    static final String BASE_URL = "https://batchdownloader.dev";
    static final String HTML = new String(resource("/seo/page.html"), StandardCharsets.UTF_8);

    private SeoStaticAssets() {}

    static Map<String, List<String>> pages() {
        try {
            return Map.copyOf(new ObjectMapper().readValue(resource("/seo/pages.json"),
                    new TypeReference<Map<String, List<String>>>() {}));
        } catch (IOException exception) {
            throw new IllegalStateException("seo_pages_invalid", exception);
        }
    }

    static byte[] resource(String path) {
        try (InputStream input = SeoStaticAssets.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("seo_resource_missing: " + path);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("seo_resource_unavailable: " + path, exception);
        }
    }

    static String cardName(String path) {
        return "/".equals(path) ? "home" : path.substring(1).replace('/', '-');
    }

    /** Maven ejecuta este generador sin iniciar Spring ni consultar el catálogo. */
    public static void main(String[] args) throws IOException {
        Path directory = Files.createDirectories(Path.of(args[0]));
        SeoService seo = new SeoService(null, new ObjectMapper(), BASE_URL);
        SocialCardRenderer renderer = new SocialCardRenderer();
        for (String path : SeoService.PUBLIC_PAGES.keySet()) {
            Files.write(directory.resolve(cardName(path) + ".png"),
                    renderer.renderDynamic(seo.resolve(path).metadata()));
        }
    }
}
