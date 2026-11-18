package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class DownloadWorkerMetricsTest {
    @Test void recordsSuccessRejectionCancellationAndFailureWithoutChangingResults() {
        var registry = new SimpleMeterRegistry();
        var metrics = new DownloadWorkerMetrics(registry);
        assertThat(metrics.measurePhase(DownloadWorkerMetrics.Phase.RESOLUTION, () -> "resolved")).isEqualTo("resolved");
        for (RuntimeException failure : new RuntimeException[]{new DownloadRejectedException("source_unavailable"),
                new CancellationException(), new IllegalStateException("private-detail")}) {
            assertThatThrownBy(() -> metrics.measurePhase(DownloadWorkerMetrics.Phase.RESOLUTION, () -> { throw failure; }))
                    .isSameAs(failure);
        }
        for (String outcome : new String[]{"success", "rejected", "cancelled", "failure"}) {
            assertThat(registry.get("download_worker_phase").tags("phase", "resolution", "outcome", outcome).timer().count()).isEqualTo(1);
        }
        assertThat(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream()).map(tag -> tag.getValue()))
                .doesNotContain("source_unavailable", "private-detail");
    }

    @Test void arbitraryCapacityReasonsAreCollapsedIntoOneBoundedLabel() {
        var registry = new SimpleMeterRegistry();
        var metrics = new DownloadWorkerMetrics(registry);
        metrics.capacityDeferred("temporary_storage_busy");
        metrics.capacityDeferred("storage_budget_wait");
        metrics.capacityDeferred("untrusted-value-one");
        metrics.capacityDeferred("untrusted-value-two");
        assertThat(registry.find("download_worker_capacity_deferred").counters()).hasSize(3);
        assertThat(registry.get("download_worker_capacity_deferred").tag("reason", "other").counter().count()).isEqualTo(2);
    }
}
