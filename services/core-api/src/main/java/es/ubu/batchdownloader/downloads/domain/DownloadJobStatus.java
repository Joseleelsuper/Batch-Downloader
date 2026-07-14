package es.ubu.batchdownloader.downloads.domain;

public enum DownloadJobStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PACKAGING,
    READY,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean terminal() {
        return this == READY || this == PARTIAL || this == FAILED || this == CANCELLED || this == EXPIRED;
    }

    public boolean downloadable() {
        return this == READY || this == PARTIAL;
    }
}
