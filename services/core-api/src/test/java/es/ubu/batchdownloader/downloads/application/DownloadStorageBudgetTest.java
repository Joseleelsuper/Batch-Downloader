package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownloadStorageBudgetTest {
    @Test void estimatesFromOwnMedianBeforeCatalogFallback() {
        assertThat(DownloadStorageBudget.estimate(Arrays.asList(10L, 30L, null, 0L), 999)).isEqualTo(80);
        assertThat(DownloadStorageBudget.estimate(Arrays.asList(null, -1L), 42)).isEqualTo(84);
        assertThat(DownloadStorageBudget.median(List.of(10L, 11L))).isEqualTo(11);
        assertThat(DownloadStorageBudget.estimate(List.of(), 0)).isZero();
        assertThat(DownloadStorageBudget.peak(1024)).isEqualTo(2048 + 17 * 1024 * 1024);
    }
    @Test void rejectsMissingSizesAndOverflowInsteadOfUnderReserving() {
        assertThatThrownBy(() -> DownloadStorageBudget.estimate(Arrays.asList((Long) null), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DownloadStorageBudget.peak(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        assertThat(DownloadStorageBudget.median(List.of(Long.MAX_VALUE - 2, Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE - 1);
    }
}
