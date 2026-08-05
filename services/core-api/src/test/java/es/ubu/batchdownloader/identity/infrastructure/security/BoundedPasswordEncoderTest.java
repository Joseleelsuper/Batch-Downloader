package es.ubu.batchdownloader.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.AuthCapacityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Verifica el presupuesto independiente de BCrypt. */
class BoundedPasswordEncoderTest {
    @Test
    void neverRunsMoreThanTwoHashesAtOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        PasswordEncoder delegate = blockingDelegate(entered, release, active, maximum);
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(
                delegate, 2, 20, Duration.ofSeconds(5));
        var callers = Executors.newFixedThreadPool(6);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                results.add(callers.submit(() -> encoder.encode("secret")));
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
            release.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("hash");
            }
        } finally {
            release.countDown();
            callers.shutdownNow();
            encoder.close();
        }
    }

    @Test
    void returnsAuthBusyWhenTheSingleQueueSlotIsFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(
                blockingDelegate(entered, release, new AtomicInteger(), new AtomicInteger()),
                1,
                1,
                Duration.ofSeconds(5));
        var callers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = callers.submit(() -> encoder.encode("first"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<String> queued = callers.submit(() -> encoder.encode("second"));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (encoder.queuedTasks() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(encoder.activeTasks()).isOne();
            assertThat(encoder.queuedTasks()).isOne();
            assertThatThrownBy(() -> encoder.encode("rejected"))
                    .isInstanceOf(AuthCapacityException.class)
                    .hasMessageContaining("autenticación está ocupado");
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("hash");
            assertThat(queued.get(2, TimeUnit.SECONDS)).isEqualTo("hash");
        } finally {
            release.countDown();
            callers.shutdownNow();
            encoder.close();
        }
    }

    private static PasswordEncoder blockingDelegate(
            CountDownLatch entered,
            CountDownLatch release,
            AtomicInteger active,
            AtomicInteger maximum) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test hash was not released");
                    }
                    return "hash";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } finally {
                    active.decrementAndGet();
                }
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encode(rawPassword).equals(encodedPassword);
            }
        };
    }
}
