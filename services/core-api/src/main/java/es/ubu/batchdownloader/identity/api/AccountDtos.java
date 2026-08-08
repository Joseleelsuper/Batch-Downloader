package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundleSummary;
import es.ubu.batchdownloader.identity.application.IdentityView;
import java.time.LocalDateTime;
import java.util.List;

/** Contratos de perfil, historial y dashboard. */
public final class AccountDtos {
    private AccountDtos() {}

    public record DownloadHistoryItem(
            String appId,
            String appName,
            String slug,
            String iconUrl,
            String jobId,
            LocalDateTime downloadedAt) {}

    public record DownloadHistoryPage(
            List<DownloadHistoryItem> data, int page, int pageSize, long total) {}

    public record DashboardCounts(
            long bundles,
            long publicBundles,
            long privateBundles,
            long downloads) {}

    public record AccountDashboard(
            IdentityView account,
            DashboardCounts counts,
            List<DownloadHistoryItem> recentDownloads,
            List<OwnBundleSummary> recentBundles) {}
}
