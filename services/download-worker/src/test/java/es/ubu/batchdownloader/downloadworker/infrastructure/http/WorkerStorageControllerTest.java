package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class WorkerStorageControllerTest {
    @TempDir Path directory;
    private final DownloadJobProcessor processor = mock(DownloadJobProcessor.class);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-token"})
    void unauthorizedRequestsNeverReadInventoryOrDeleteFiles(String token) {
        var controller = controller("internal-secret", directory, 0);
        assertThatThrownBy(() -> controller.inventory(token)).isInstanceOfSatisfying(
                ResponseStatusException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.clean(UUID.randomUUID(), token)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(processor);
    }

    @Test
    void blankConfiguredTokenCannotAuthorizeCleanup() {
        assertThatThrownBy(() -> controller(" ", directory, 0).clean(UUID.randomUUID(), " "))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(processor);
    }

    @Test
    void inventoryIncludesActiveJobsBeforeTheirFirstByteAndAppliesPhysicalMargin() throws Exception {
        UUID stored = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        when(processor.usage()).thenAnswer(invocation -> new HashMap<>(Map.of(stored, 512L)));
        when(processor.activeJobs()).thenReturn(Set.of(active));
        when(processor.active(active)).thenReturn(true);
        var inventory = controller("internal-secret", directory.resolve("new-base"), Long.MAX_VALUE)
                .inventory("internal-secret");
        assertThat(inventory.jobs()).containsExactlyInAnyOrder(
                new WorkerStorageController.Job(stored, 512, false),
                new WorkerStorageController.Job(active, 0, true));
        assertThat(inventory.availableBytes()).isZero();
        assertThat(controller("internal-secret", directory, 0).inventory("internal-secret").availableBytes())
                .isPositive().isLessThanOrEqualTo(Files.getFileStore(directory).getTotalSpace());
    }

    @Test
    void cleanupReturnsNoContentOnlyAfterTheProcessorConfirmsDeletion() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller("internal-secret", directory, 0)).build();
        UUID jobId = UUID.randomUUID();
        mvc.perform(delete("/internal/v1/jobs/{id}/files", jobId)
                .header("X-Internal-Service-Token", "internal-secret")).andExpect(status().isNoContent());
        verify(processor).clean(jobId);
        var failure = new InfrastructureException("minio_cleanup_failed", new IllegalStateException("busy"));
        doThrow(failure).when(processor).clean(jobId);
        assertThatThrownBy(() -> mvc.perform(delete("/internal/v1/jobs/{id}/files", jobId)
                .header("X-Internal-Service-Token", "internal-secret"))).hasCause(failure);
    }

    @Test
    void inventoryDoesNotReportFreeCapacityWhenTheStoragePathIsInvalid() throws Exception {
        Path invalidBase = Files.writeString(directory.resolve("file-not-directory"), "occupied");
        when(processor.usage()).thenReturn(new HashMap<>());
        when(processor.activeJobs()).thenReturn(Set.of());
        assertThatThrownBy(() -> controller("internal-secret", invalidBase, 0).inventory("internal-secret"))
                .isInstanceOf(java.io.IOException.class);
    }

    private WorkerStorageController controller(String token, Path base, long margin) {
        var properties = new DownloadProperties(100, DataSize.ofGigabytes(4), 5, Duration.ofSeconds(10),
                Duration.ofMinutes(15), 2, 2, 0, DataSize.ofBytes(margin), DataSize.ofMegabytes(16),
                Duration.ofMinutes(30), base.toString());
        return new WorkerStorageController(processor,
                new CoreApiProperties("http://core-api", token, Duration.ofSeconds(10)), properties);
    }
}
