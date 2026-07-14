package es.ubu.batchdownloader.downloads.application.port;

import java.util.UUID;

/** Removes the ZIP, manifest and any failed-to-clean staging objects for an expired job. */
public interface DownloadArtifactCleaner {
    void deleteJobArtifacts(UUID jobId);
}
