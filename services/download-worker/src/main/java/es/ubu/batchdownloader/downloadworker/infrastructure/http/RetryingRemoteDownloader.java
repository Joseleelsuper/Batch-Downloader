package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Reintenta hasta dos veces rechazos temporales con espera acotada y limpieza previa del parcial,
 * cargando al presupuesto total solo una transferencia completada.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class RetryingRemoteDownloader implements RemoteDownloader {
    private static final int MAX_RETRIES = 2;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final Set<String> RETRYABLE_EXACT = Set.of(
            "remote_timeout", "remote_http_408", "remote_http_429");
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;

    /**
     * Conecta la transferencia que se reintentará y los contadores por motivo.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     * @param registry Registro de duraciones, concurrencia y reintentos.
     */
    public RetryingRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Utiliza presupuesto propio por intento y suma al total solo el artefacto completado; ante un
     * rechazo reintentable limpia el parcial, registra el motivo y espera antes de repetir.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto del intento satisfactorio.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     motivo no admite reintento, se agotaron tres intentos o se interrumpe la espera.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        for (int attempt = 0; ; attempt++) {
            try {
                DownloadBudget attemptBudget = new DownloadBudget(maxFileBytes);
                DownloadedArtifact artifact = delegate.download(
                        item, filename, target, attemptBudget, maxFileBytes);
                totalBudget.consume(artifact.sizeBytes());
                return artifact;
            } catch (DownloadRejectedException exception) {
                if (attempt >= MAX_RETRIES || !retryable(exception.code())) throw exception;
                deletePartial(target, exception);
                registry.counter("download_worker_remote_retries", "reason", exception.code()).increment();
                pause(delay(exception, attempt));
            }
        }
    }

    /**
     * Admite los códigos temporales explícitos y estados HTTP entre 500 y 599; descarta sufijos
     * numéricos inválidos.
     *
     * @param code Código seguro del rechazo que se clasifica como temporal o definitivo.
     * @return true si el rechazo permite otro intento.
     */
    private boolean retryable(String code) {
        if (RETRYABLE_EXACT.contains(code)) return true;
        if (!code.startsWith("remote_http_5")) return false;
        try {
            int status = Integer.parseInt(code.substring("remote_http_".length()));
            return status >= 500 && status <= 599;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * Prioriza Retry-After y usa espera exponencial si falta, acotando siempre el resultado entre
     * cero y treinta segundos.
     *
     * @param exception Rechazo que puede aportar Retry-After para la espera siguiente.
     * @param attempt Índice del intento fallido, empezando en cero.
     * @return duración efectiva antes del siguiente intento.
     */
    private Duration delay(DownloadRejectedException exception, int attempt) {
        Duration requested = exception.retryAfter();
        if (requested == null) requested = Duration.ofSeconds(1L << attempt);
        if (requested.isNegative()) return Duration.ZERO;
        return requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested;
    }

    /**
     * Espera la demora elegida y conserva el estado de interrupción si el hilo se cancela.
     *
     * @param duration Duración acotada de espera antes de volver a descargar.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si se
     *     interrumpe la espera.
     */
    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadRejectedException("download_interrupted", exception);
        }
    }

    /**
     * Borra el archivo anterior antes de reintentar; si falla el borrado propaga el rechazo
     * original con la causa de limpieza suprimida.
     *
     * @param target Ruta local de destino del instalador.
     * @param original Fallo de descarga que se conserva si también falla el borrado del parcial.
     */
    private void deletePartial(Path target, RuntimeException original) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            original.addSuppressed(exception);
            throw original;
        }
    }
}
