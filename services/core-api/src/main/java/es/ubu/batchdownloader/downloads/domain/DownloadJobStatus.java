package es.ubu.batchdownloader.downloads.domain;

public enum DownloadJobStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PACKAGING,
    READY,
    PARTIAL,
    MANUAL_ONLY,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean terminal() {
        return this == READY
                || this == PARTIAL
                || this == MANUAL_ONLY
                || this == FAILED
                || this == CANCELLED
                || this == EXPIRED;
    }

    public boolean downloadable() {
        return this == READY || this == PARTIAL || this == MANUAL_ONLY;
    }
}
