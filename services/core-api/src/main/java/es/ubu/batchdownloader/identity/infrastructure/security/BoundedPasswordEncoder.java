package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.AuthCapacityException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Ejecuta BCrypt en un conjunto acotado para que una ráfaga no agote los hilos HTTP.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
final class BoundedPasswordEncoder implements PasswordEncoder, AutoCloseable {
    /** Codificador BCrypt real. */
    private final PasswordEncoder delegate;
    /** Ejecutor con concurrencia y cola limitadas. */
    private final ThreadPoolExecutor executor;
    /** Espera máxima del llamante. */
    private final Duration wait;

    /**
     * Inicializa el codificador acotado.
     *
     * @param delegate Codificador real.
     * @param concurrency Número máximo de cálculos simultáneos.
     * @param queueCapacity Número máximo de cálculos pendientes.
     * @param wait Espera máxima de cada solicitud.
     */
    BoundedPasswordEncoder(
            PasswordEncoder delegate,
            int concurrency,
            int queueCapacity,
            Duration wait) {
        this.delegate = delegate;
        this.wait = wait;
        AtomicInteger sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "bcrypt-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** {@inheritDoc} */
    @Override
    public String encode(CharSequence rawPassword) {
        return execute(() -> delegate.encode(rawPassword));
    }

    /** {@inheritDoc} */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return execute(() -> delegate.matches(rawPassword, encodedPassword));
    }

    /** {@inheritDoc} */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    /**
     * Ejecuta una operación dentro del presupuesto de BCrypt.
     *
     * @param operation Operación que debe ejecutarse.
     * @param <T> Tipo del resultado.
     * @return Resultado de la operación.
     */
    private <T> T execute(java.util.concurrent.Callable<T> operation) {
        Future<T> future;
        try {
            future = executor.submit(operation);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            throw new AuthCapacityException();
        }
        try {
            return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AuthCapacityException();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AuthCapacityException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("password_encoding_failed", cause);
        }
    }

    /** @return Número de cálculos BCrypt activos. */
    int activeTasks() {
        return executor.getActiveCount();
    }

    /** @return Número de cálculos en espera. */
    int queuedTasks() {
        return executor.getQueue().size();
    }

    /** Detiene los hilos de cálculo al cerrar Spring. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
