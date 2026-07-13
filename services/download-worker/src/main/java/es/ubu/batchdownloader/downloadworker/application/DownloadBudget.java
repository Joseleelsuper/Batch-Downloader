package es.ubu.batchdownloader.downloadworker.application;

import java.util.concurrent.atomic.AtomicLong;

public final class DownloadBudget {
    private final long maxTotalBytes;
    private final AtomicLong consumedBytes = new AtomicLong();

    public DownloadBudget(long maxTotalBytes) {
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes must be positive");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    public void consume(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        long total = consumedBytes.addAndGet(bytes);
        if (total > maxTotalBytes) {
            throw new DownloadRejectedException("total_size_limit_exceeded");
        }
    }

    public long consumedBytes() {
        return consumedBytes.get();
    }
}
