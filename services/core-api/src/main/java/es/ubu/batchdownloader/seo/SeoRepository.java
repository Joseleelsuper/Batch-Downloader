package es.ubu.batchdownloader.seo;

import es.ubu.batchdownloader.common.UuidBytes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lee únicamente metadatos públicos; cada consulta vuelve a comprobar su visibilidad. */
@Repository
public class SeoRepository {
    static final int SITEMAP_PAGE_SIZE = 1_000;
    private final JdbcTemplate jdbc;

    public SeoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Page(UUID id, String name, String description, String publisher) {}
    public record SitemapEntry(UUID id, LocalDateTime updatedAt) {}

    public Optional<Page> app(String identifier) {
        byte[] id = uuid(identifier);
        return jdbc.query("""
                SELECT id, name, description, publisher FROM software_apps
                WHERE app_status = 'active'
                  AND ((? IS NOT NULL AND id = ?) OR slug = ? OR winstall_id = ?)
                LIMIT 1
                """, (rs, row) -> new Page(UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getString("name"), rs.getString("description"), rs.getString("publisher")),
                id, id, identifier, identifier).stream().findFirst();
    }

    public Optional<Page> bundle(String identifier) {
        byte[] id = uuid(identifier);
        return jdbc.query("""
                SELECT id, name, description FROM bundles
                WHERE visibility IN ('public', 'official')
                  AND ((? IS NOT NULL AND id = ?) OR slug = ?)
                LIMIT 1
                """, (rs, row) -> new Page(UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getString("name"), rs.getString("description"), null),
                id, id, identifier).stream().findFirst();
    }

    /** Autoriza solo el shell genérico, sin leer nombre, descripción ni contenido privado. */
    public boolean privateBundleAccessible(String identifier, UUID viewerId, boolean administrator) {
        byte[] id = uuid(identifier);
        return !jdbc.query("""
                SELECT id FROM bundles
                WHERE visibility = 'private'
                  AND ((? IS NOT NULL AND id = ?) OR slug = ?)
                  AND (? = TRUE OR owner_id = ?)
                LIMIT 1
                """, (rs, row) -> rs.getBytes("id"),
                id, id, identifier, administrator, viewerId.toString()).isEmpty();
    }

    public long sitemapCount(String group) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + sitemapSource(group), Long.class);
        return count == null ? 0 : count;
    }

    /** Una página acotada evita cargar el catálogo entero al servir un sitemap. */
    public List<SitemapEntry> sitemapEntries(String group, int page) {
        return jdbc.query("SELECT id, updated_at FROM " + sitemapSource(group)
                        + " ORDER BY id LIMIT ? OFFSET ?",
                (rs, row) -> new SitemapEntry(UuidBytes.toUuid(rs.getBytes("id")),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                SITEMAP_PAGE_SIZE, (long) (page - 1) * SITEMAP_PAGE_SIZE);
    }

    private static String sitemapSource(String group) {
        return switch (group) {
            case "apps" -> "software_apps WHERE app_status = 'active'";
            case "bundles" -> "bundles WHERE visibility IN ('public', 'official')";
            default -> throw new IllegalArgumentException("invalid_sitemap_group");
        };
    }

    private static byte[] uuid(String value) {
        try {
            return UuidBytes.fromUuid(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
