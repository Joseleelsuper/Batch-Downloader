package es.ubu.batchdownloader.downloads.application;

import java.util.List;

/** Cálculo de reservas físicas: instaladores, ZIP y margen de multipart/metadatos. */
public final class DownloadStorageBudget {
    public static final long LIMIT = 10L * 1024 * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    private DownloadStorageBudget() {}

    public static long median(List<Long> sizes) {
        var sorted = sizes.stream().filter(n -> n != null && n > 0).sorted().toList();
        if (sorted.isEmpty()) return 0;
        long upper = sorted.get(sorted.size() / 2);
        if (sorted.size() % 2 == 1) return upper;
        long lower = sorted.get(sorted.size() / 2 - 1);
        return lower + (upper - lower) / 2 + (upper - lower) % 2;
    }

    public static long estimate(List<Long> sizes, long catalogMedian) {
        long median = median(sizes);
        if (median == 0) median = catalogMedian;
        long total = 0;
        for (Long size : sizes) {
            long bytes = size != null && size > 0 ? size : median;
            if (bytes <= 0) throw new IllegalArgumentException("download_size_unavailable");
            total = Math.addExact(total, bytes);
        }
        return total;
    }

    public static long peak(long installers) {
        return Math.addExact(Math.multiplyExact(installers, 2),
                Math.addExact(Math.max(MIB, installers / 100), 16 * MIB));
    }
}
