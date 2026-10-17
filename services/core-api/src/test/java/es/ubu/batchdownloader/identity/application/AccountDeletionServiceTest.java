package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountDeletionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void disablesTheAccountRemovesVisibleDataAndDelegatesJobCleanup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DownloadJobStore jobs = mock(DownloadJobStore.class);
        DownloadJobAccessService downloads = mock(DownloadJobAccessService.class);
        DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
        UUID userId = UUID.randomUUID();
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).startsWith("SELECT enabled")
                        ? List.of(true) : List.of(firstJob, secondJob));
        AccountDeletionService service = service(jdbc, jobs, downloads, storage);

        service.request(userId);

        InOrder admission = inOrder(jobs, jdbc);
        admission.verify(jobs).lockAdmission();
        admission.verify(jdbc).queryForObject(
                "SELECT COUNT(*) FROM account_deletion_requests WHERE user_id = ?",
                Integer.class, userId.toString());
        Timestamp requestedAt = Timestamp.from(NOW);
        verify(jdbc).update(
                "UPDATE core_users SET enabled = FALSE, updated_at = ?, version = version + 1 WHERE id = ?",
                requestedAt, userId.toString());
        verify(jdbc).update(
                "INSERT INTO account_deletion_requests (user_id, requested_at) VALUES (?, ?)",
                userId.toString(), requestedAt);
        verify(jdbc).update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", userId.toString());
        verify(jdbc).update("DELETE FROM bundles WHERE owner_id = ?", userId.toString());
        verify(jdbc).update("DELETE FROM user_download_history WHERE user_id = ?", userId.toString());
        verify(jdbc).update("DELETE FROM download_job_receipts WHERE owner_id = ?", userId.toString());
        verify(jdbc).update("DELETE FROM identity_tokens WHERE user_id = ?", userId.toString());
        verify(downloads).cancel(new RequestOwner(userId, null, null), firstJob);
        verify(downloads).cancel(new RequestOwner(userId, null, null), secondJob);
        verify(storage).requestAccountJobCleanup(userId);
    }

    @Test
    void repeatedRequestDoesNotCancelJobsAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DownloadJobStore jobs = mock(DownloadJobStore.class);
        DownloadJobAccessService downloads = mock(DownloadJobAccessService.class);
        DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        service(jdbc, jobs, downloads, storage).request(userId);

        verify(jobs).lockAdmission();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(downloads, storage);
    }

    @Test
    void disabledOrMissingUserCannotRequestDeletion() {
        for (List<Boolean> enabled : List.of(List.<Boolean>of(), List.of(false))) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DownloadJobStore jobs = mock(DownloadJobStore.class);
            DownloadJobAccessService downloads = mock(DownloadJobAccessService.class);
            DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(enabled);

            assertThatThrownBy(() -> service(jdbc, jobs, downloads, storage).request(UUID.randomUUID()))
                    .isInstanceOfSatisfying(UnauthorizedException.class,
                            error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                    .isEqualTo("unauthorized"));
            verify(jobs).lockAdmission();
            verify(jdbc, never()).update(anyString(), any(Object[].class));
            verifyNoInteractions(downloads, storage);
        }
    }

    private static AccountDeletionService service(
            JdbcTemplate jdbc, DownloadJobStore jobs, DownloadJobAccessService downloads,
            DownloadStorageCoordinator storage) {
        return new AccountDeletionService(jdbc, jobs, downloads, storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
