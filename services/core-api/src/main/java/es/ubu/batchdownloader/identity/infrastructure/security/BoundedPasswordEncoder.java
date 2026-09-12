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
 * Limita hilos, cola y espera de los cálculos de contraseña para devolver falta temporal de
 * capacidad sin saturar los hilos HTTP.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig
 * @see es.ubu.batchdownloader.common.AuthCapacityException
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
final class BoundedPasswordEncoder implements PasswordEncoder, AutoCloseable {
    /** Codificador BCrypt real. */
    private final PasswordEncoder delegate;
    /** Ejecutor con concurrencia y cola limitadas. */
    private final ThreadPoolExecutor executor;
    /** Espera máxima del llamante. */
    private final Duration wait;

    /**
     * Crea un pool fijo de hilos daemon y una cola acotada con rechazo inmediato cuando se llena.
     *
     * @param delegate Codificador que realiza el cálculo criptográfico en los hilos reservados.
     * @param concurrency Número fijo de cálculos criptográficos que pueden ejecutarse
     *     simultáneamente.
     * @param queueCapacity Máximo de cálculos en espera antes de rechazar por falta de capacidad.
     * @param wait Plazo máximo de espera por el resultado, incluyendo el tiempo en cola.
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

    /**
     * Encola el cálculo del hash y espera como máximo el plazo configurado.
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @return hash del codificador delegado.
     * @throws es.ubu.batchdownloader.common.AuthCapacityException si no hay espacio, vence la
     *     espera o el hilo solicitante se interrumpe.
     */
    @Override
    public String encode(CharSequence rawPassword) {
        return execute(() -> delegate.encode(rawPassword));
    }

    /**
     * Encola la comprobación de contraseña bajo el mismo límite de capacidad y espera.
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @param encodedPassword Hash calculado antes de actualizar el agregado de cuenta.
     * @return resultado de la comparación delegada.
     */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return execute(() -> delegate.matches(rawPassword, encodedPassword));
    }

    /**
     * Consulta directamente si el hash requiere actualización sin consumir un cálculo en el pool.
     *
     * @param encodedPassword Hash calculado antes de actualizar el agregado de cuenta.
     * @return decisión del codificador delegado.
     */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    /**
     * Reserva capacidad, espera el resultado y solicita cancelación ante timeout o interrupción;
     * conserva la marca de interrupción y propaga las excepciones de ejecución no comprobadas.
     *
     * @param operation Cálculo de hash o verificación que se ejecuta en el pool acotado.
     * @param <T> Tipo del resultado del cálculo criptográfico.
     * @return resultado de la operación delegada.
     * @throws es.ubu.batchdownloader.common.AuthCapacityException si el pool rechaza el trabajo o
     *     la espera no puede completarse.
     * @throws IllegalStateException si el cálculo falla con una causa comprobada.
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

    /**
     * Consulta los cálculos actualmente ejecutándose en el pool.
     *
     * @return número de hilos activos.
     */
    int activeTasks() {
        return executor.getActiveCount();
    }

    /**
     * Consulta los cálculos que aún esperan un hilo disponible.
     *
     * @return tamaño actual de la cola.
     */
    int queuedTasks() {
        return executor.getQueue().size();
    }

    /**
     * Solicita la interrupción de los cálculos y detiene el pool al cerrar el componente.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
