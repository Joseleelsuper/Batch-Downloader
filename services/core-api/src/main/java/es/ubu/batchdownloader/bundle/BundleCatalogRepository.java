package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.BundleAlphabet;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleCatalogResponse;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleFacet;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleFacets;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleStarState;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import es.ubu.batchdownloader.common.UuidBytes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Comparte filtros parametrizados entre página, recuentos y facetas de bundles visibles. */
@Repository
public class BundleCatalogRepository {
    private final JdbcTemplate jdbc;
    private final BundleReadRepository reads;
    private final BundleActivityRepository activity;

    public BundleCatalogRepository(JdbcTemplate jdbc, BundleReadRepository reads,
            BundleActivityRepository activity) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.activity = activity;
    }

    public record Filters(String type, String query, List<String> tags, String creator,
            List<String> operatingSystems, String searchMode) {}

    @Transactional(readOnly = true)
    public BundleCatalogResponse search(Filters filters, String sort, int page, int pageSize,
            UUID viewer, SemanticCandidateSet candidates) {
        Sql base = filtered(filters, candidates, false, false, false);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", 0L);
        counts.put("public", 0L);
        counts.put("official", 0L);
        jdbc.query("SELECT CASE WHEN b.type = 'official' THEN 'official' ELSE 'public' END AS category, COUNT(*) AS amount "
                        + base.text() + " GROUP BY category",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> counts.put(row.getString("category"), row.getLong("amount")),
                base.params().toArray());
        counts.put("all", counts.get("public") + counts.get("official"));
        Sql selected = filtered(filters, candidates, true, false, false);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + selected.text(), Long.class, selected.params().toArray());
        String order = switch (sort) {
            case "stars" -> "b.star_count DESC, b.updated_at DESC, b.id";
            case "downloads" -> "b.download_count DESC, b.updated_at DESC, b.id";
            case "name" -> "b.name, b.id";
            case "relevance" -> candidates.semantic() ? "ranked.semantic_rank, b.id" : "b.updated_at DESC, b.id";
            default -> "b.updated_at DESC, b.id";
        };
        List<Object> pageParams = new ArrayList<>(selected.params());
        pageParams.add(pageSize);
        pageParams.add((long) (page - 1) * pageSize);
        List<UUID> ids = jdbc.query("SELECT b.id " + selected.text() + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, n) -> UuidBytes.toUuid(rs.getBytes(1)), pageParams.toArray());
        List<BundleSummary> bundles = reads.summaries(ids);
        Map<String, BundleStarState> states = activity.states(bundles.stream().map(BundleSummary::id).toList(), viewer);
        List<BundleAlphabet> alphabet = new ArrayList<>();
        if ("name".equals(sort)) {
            String initial = "UPPER(LEFT(b.name,1))";
            for (String group : List.of("AÁÀÄÂÃÅ", "EÉÈËÊ", "IÍÌÏÎ", "OÓÒÖÔÕ", "UÚÙÜÛ", "NÑ", "CÇ")) {
                for (int i = 1; i < group.length(); i++) initial = "REPLACE(" + initial + ", '" + group.charAt(i) + "', '" + group.charAt(0) + "')";
            }
            String letter = "CASE WHEN " + initial + " REGEXP '^[A-Z]$' THEN " + initial + " ELSE '#' END";
            alphabet.addAll(jdbc.query("SELECT letter, COUNT(*) AS amount, MIN(row_position) AS first_position FROM (SELECT "
                            + letter + " AS letter, ROW_NUMBER() OVER(ORDER BY b.name,b.id) AS row_position "
                            + selected.text() + ") letters GROUP BY letter ORDER BY first_position",
                    (rs, n) -> new BundleAlphabet(rs.getString("letter"),
                            (int) ((rs.getLong("first_position") - 1) / pageSize) + 1, rs.getLong("amount")),
                    selected.params().toArray()));
        }
        return new BundleCatalogResponse(bundles.stream().map(bundle -> bundle.withActivity(
                        states.getOrDefault(bundle.id(), BundleStarState.HIDDEN))).toList(), page, pageSize,
                total == null ? 0 : total, counts, alphabet, candidates.requestedMode().wireValue(),
                candidates.appliedMode().wireValue(), candidates.modelVersion(), candidates.indexVersion(), candidates.degradedReason());
    }

    @Transactional(readOnly = true)
    public BundleFacets facets(Filters filters, SemanticCandidateSet candidates) {
        Sql tags = filtered(filters, candidates, true, true, false);
        List<BundleFacet> tagItems = jdbc.query("SELECT t.normalized_tag AS value, MIN(t.tag) AS label, COUNT(DISTINCT b.id) AS amount "
                        + tags.text() + " GROUP BY t.normalized_tag ORDER BY label",
                (rs, n) -> facet(rs.getString("value"), rs.getString("label"), rs.getLong("amount")), tags.params().toArray());
        return new BundleFacets(tagItems, creatorFacets(filters, candidates));
    }

    private List<BundleFacet> creatorFacets(Filters filters, SemanticCandidateSet candidates) {
        Sql sql = filtered(filters, candidates, true, false, true);
        return jdbc.query("SELECT owner.id AS value, owner.username AS label, COUNT(DISTINCT b.id) AS amount "
                        + sql.text() + " AND owner.id IS NOT NULL GROUP BY owner.id, owner.username ORDER BY owner.username",
                (rs, n) -> facet(rs.getString("value"), rs.getString("label"), rs.getLong("amount")), sql.params().toArray());
    }

    private static BundleFacet facet(String value, String label, long count) {
        String first = label.substring(0, 1).toUpperCase(Locale.ROOT);
        return new BundleFacet(value, label, first.matches("[A-Z]") ? first : "#", count);
    }

    private record Sql(String text, List<Object> params) {}

    private Sql filtered(Filters f, SemanticCandidateSet candidates, boolean includeType,
            boolean omitTags, boolean omitCreator) {
        StringBuilder sql = new StringBuilder("FROM bundles b LEFT JOIN core_users owner ON owner.id = b.owner_id ");
        List<Object> params = new ArrayList<>();
        if (candidates.semantic()) {
            sql.append("JOIN JSON_TABLE(?, '$[*]' COLUMNS(candidate_id CHAR(36) PATH '$.appId', semantic_rank INT PATH '$.rank')) ranked ON b.id = UUID_TO_BIN(ranked.candidate_id) ");
            params.add(candidates.candidatesJson());
        }
        if (omitTags) sql.append("JOIN bundle_tags t ON t.bundle_id = b.id ");
        sql.append("WHERE b.visibility IN ('public','official') ");
        if (includeType && f.type() != null && !f.type().isBlank() && !"all".equals(f.type())) {
            if (List.of("community", "public").contains(f.type())) sql.append("AND b.type IN ('community','user') ");
            else { sql.append("AND b.type = ? "); params.add(f.type()); }
        }
        if (!candidates.semantic() && f.query() != null && !f.query().isBlank()) {
            sql.append("AND (b.name LIKE ? OR b.description LIKE ? OR EXISTS(SELECT 1 FROM bundle_tags q WHERE q.bundle_id=b.id AND q.tag LIKE ?)) ");
            String like = "%" + f.query().trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (!omitCreator && f.creator() != null && !f.creator().isBlank()) {
            sql.append("AND b.owner_id = ? "); params.add(f.creator());
        }
        if (!omitTags && f.tags() != null) {
            for (String tag : f.tags().stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toLowerCase(Locale.ROOT)).distinct().toList()) {
                sql.append("AND EXISTS(SELECT 1 FROM bundle_tags t WHERE t.bundle_id=b.id AND t.normalized_tag=?) "); params.add(tag);
            }
        }
        if (f.operatingSystems() != null && !f.operatingSystems().isEmpty()) {
            sql.append("""
                    AND EXISTS(SELECT 1 FROM bundle_items item
                      JOIN software_apps app ON app.id=item.software_app_id
                      JOIN download_sources source ON source.software_app_id=app.id
                      JOIN resolved_sources artifact ON artifact.download_source_id=source.id
                      WHERE item.bundle_id=b.id AND app.app_status='active' AND app.catalog_status='available'
                        AND source.catalog_available=1 AND artifact.catalog_downloadable=1
                        AND source.resolution_status IN ('direct','fallback') AND source.validation_status='valid'
                        AND source.operating_system IN (
                    """);
            sql.append(String.join(",", java.util.Collections.nCopies(f.operatingSystems().size(), "?"))).append(")) ");
            params.addAll(f.operatingSystems());
        }
        return new Sql(sql.toString(), params);
    }
}
