package es.ubu.batchdownloader.softwarerequest;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.SoftwareRequest;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.SoftwareRequestPage;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.ReviewSoftwareRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Mantiene propuestas personales y una revisión administrativa; no escribe en el catálogo. */
@Repository
public class SoftwareRequestRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SoftwareRequestRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public SoftwareRequest create(UUID owner, String name, String officialUrl) {
        var input = SoftwareRequestInput.parse(name, officialUrl);
        var enabled = jdbc.query("SELECT enabled FROM core_users WHERE id=? AND role='USER' FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), owner.toString());
        if (enabled.isEmpty() || !enabled.getFirst()) throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM software_apps WHERE app_status='active' AND (LOWER(TRIM(name))=? OR TRIM(TRAILING '/' FROM official_url)=TRIM(TRAILING '/' FROM ?))",
                Long.class, input.normalizedName(), input.officialUrl());
        if (existing != null && existing > 0) throw new ConflictException("software_already_catalogued", "La aplicación ya figura en el catálogo.");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM software_inclusion_requests WHERE owner_id=?", Long.class, owner.toString());
        if (count != null && count >= 50) throw new ConflictException("software_request_limit", "Has alcanzado el límite de 50 solicitudes.");
        UUID id = UUID.randomUUID();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            jdbc.update("INSERT INTO software_inclusion_requests(id,owner_id,name,normalized_name,official_url,url_key,created_at) VALUES(?,?,?,?,?,?,?)",
                    id.toString(), owner.toString(), input.name(), input.normalizedName(), input.officialUrl(), input.urlKey(), now);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("software_request_duplicate", "Ya has solicitado esta aplicación o página oficial.");
        }
        return find(id);
    }

    @Transactional(readOnly = true)
    public SoftwareRequestPage list(UUID owner, String status, int page, int pageSize) {
        if (status != null && !Set.of("pending", "accepted", "rejected").contains(status)) {
            throw new BadRequestException("invalid_software_request_status", "Estado de revisión no válido.");
        }
        int safePage = Math.max(1, Math.min(1_000_000, page));
        int safeSize = Math.max(1, Math.min(50, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (owner != null) { where.append(" AND owner_id=?"); params.add(owner.toString()); }
        if (status != null) { where.append(" AND status=?"); params.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM software_inclusion_requests" + where, Long.class, params.toArray());
        params.add(safeSize);
        params.add((long) (safePage - 1) * safeSize);
        var data = jdbc.query("SELECT * FROM software_inclusion_requests" + where + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                SoftwareRequestRepository::map, params.toArray());
        return new SoftwareRequestPage(data, safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional
    public void cancel(UUID owner, UUID id) {
        int deleted = jdbc.update("DELETE FROM software_inclusion_requests WHERE id=? AND owner_id=? AND status='pending'",
                id.toString(), owner.toString());
        if (deleted > 0) return;
        Long owned = jdbc.queryForObject("SELECT COUNT(*) FROM software_inclusion_requests WHERE id=? AND owner_id=?",
                Long.class, id.toString(), owner.toString());
        if (owned == null || owned == 0) {
            throw new NotFoundException("software_request_not_found", "Solicitud no encontrada.");
        }
        throw new ConflictException("software_request_not_pending", "La solicitud ya ha sido revisada y no se puede cancelar.");
    }

    @Transactional
    public SoftwareRequest review(UUID id, UUID reviewer, ReviewSoftwareRequest request) {
        if (request.status() == null || !Set.of("accepted", "rejected").contains(request.status()) || request.reviewNote() == null
                || request.reviewNote().isBlank() || request.reviewNote().length() > 1000
                || request.version() == null || request.version() < 0) {
            throw new BadRequestException("invalid_software_request_review", "Indica una decisión y su motivo.");
        }
        int changed = jdbc.update("UPDATE software_inclusion_requests SET status=?,review_note=?,reviewed_by=?,reviewed_at=?,version=version+1 WHERE id=? AND version=?",
                request.status(), request.reviewNote().strip(), reviewer.toString(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), id.toString(), request.version());
        if (changed == 0) {
            find(id);
            throw new ConflictException("software_request_changed", "Otra revisión ha cambiado esta solicitud. Vuelve a cargarla.");
        }
        return find(id);
    }

    private SoftwareRequest find(UUID id) {
        return jdbc.query("SELECT * FROM software_inclusion_requests WHERE id=?", SoftwareRequestRepository::map, id.toString())
                .stream().findFirst().orElseThrow(() -> new NotFoundException("software_request_not_found", "Solicitud no encontrada."));
    }

    private static SoftwareRequest map(ResultSet row, int index) throws SQLException {
        var reviewed = row.getTimestamp("reviewed_at");
        return new SoftwareRequest(UUID.fromString(row.getString("id")), row.getString("name"),
                row.getString("official_url"), row.getString("status"), row.getString("review_note"),
                row.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                reviewed == null ? null : reviewed.toLocalDateTime().toInstant(ZoneOffset.UTC), row.getLong("version"));
    }
}
