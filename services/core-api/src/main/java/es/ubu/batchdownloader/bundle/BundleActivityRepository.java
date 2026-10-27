package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleStarState;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Mantiene votos idempotentes y obtiene la actividad de toda una página en una consulta. */
@Repository
public class BundleActivityRepository {
    private final JdbcTemplate jdbc;

    public BundleActivityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, BundleStarState> states(List<String> ids, UUID viewer) {
        if (ids.isEmpty()) return Map.of();
        List<Object> params = new ArrayList<>();
        params.add(viewer == null ? null : viewer.toString());
        ids.forEach(id -> params.add(UuidBytes.fromUuid(UUID.fromString(id))));
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<String, BundleStarState> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT b.id, b.visibility, b.star_count, b.download_count,
                    EXISTS(SELECT 1 FROM bundle_stars s WHERE s.bundle_id = b.id AND s.user_key = ?) AS starred,
                    EXISTS(SELECT 1 FROM bundle_stars s JOIN core_users u ON u.id = s.user_key
                        WHERE s.bundle_id = b.id AND u.role = 'ADMIN' AND u.enabled = TRUE) AS admin_star
                FROM bundles b WHERE b.id IN (%s)
                """.formatted(placeholders), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    boolean visible = List.of("public", "official").contains(row.getString("visibility"));
                    result.put(UuidBytes.toUuid(row.getBytes("id")).toString(), visible
                            ? new BundleStarState(row.getInt("star_count"), row.getBoolean("starred"),
                                    row.getBoolean("admin_star"), row.getLong("download_count"))
                            : BundleStarState.HIDDEN);
                }, params.toArray());
        return result;
    }

    /** Cuenta y bundle se bloquean en ese orden, compartido con el borrado de cuenta. */
    @Transactional
    public BundleStarState setStar(String publicId, UUID userId, boolean starred) {
        List<Boolean> enabled = jdbc.query("SELECT enabled FROM core_users WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getBoolean(1), userId.toString());
        if (enabled.isEmpty() || !enabled.getFirst()) {
            throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
        }
        List<UUID> ids = jdbc.query("""
                SELECT id FROM bundles WHERE (id = ? OR slug = ?)
                    AND visibility IN ('public', 'official') LIMIT 1 FOR UPDATE
                """, (rs, n) -> UuidBytes.toUuid(rs.getBytes(1)), BundleValues.uuidBytesOrNull(publicId), publicId);
        if (ids.isEmpty()) throw new NotFoundException("bundle_not_found", "El bundle no existe.");
        UUID id = ids.getFirst();
        if (starred) {
            jdbc.update("""
                    INSERT INTO bundle_stars(id, bundle_id, user_key, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP())
                    ON DUPLICATE KEY UPDATE user_key = VALUES(user_key)
                    """, UuidBytes.fromUuid(UUID.randomUUID()), UuidBytes.fromUuid(id), userId.toString());
        } else {
            jdbc.update("DELETE FROM bundle_stars WHERE bundle_id = ? AND user_key = ?",
                    UuidBytes.fromUuid(id), userId.toString());
        }
        jdbc.update("UPDATE bundles SET star_count = (SELECT COUNT(*) FROM bundle_stars WHERE bundle_id = ?) WHERE id = ?",
                UuidBytes.fromUuid(id), UuidBytes.fromUuid(id));
        return states(List.of(id.toString()), userId).get(id.toString());
    }
}
