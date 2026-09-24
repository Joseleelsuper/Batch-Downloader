package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transfiere una respuesta binaria a disco con redirecciones acotadas, límites por archivo y
 * trabajo y cálculo progresivo de SHA-256. Delega la seguridad de cada URI al intercambio HTTP
 * compuesto.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteExchange
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsRemoteExchange
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class DefaultRemoteDownloader implements RemoteDownloader {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultRemoteDownloader.class);
    private final RemoteExchange exchange;
    private final DownloadProperties properties;

    /**
     * Conecta el intercambio HTTP y los límites efectivos de la transferencia.
     *
     * @param exchange Transporte de respuestas al que se delega cada URI, incluidas las
     *     redirecciones.
     * @param properties Límites de tamaño, tiempo y redirecciones configurados para la
     *     transferencia.
     */
    public DefaultRemoteDownloader(RemoteExchange exchange, DownloadProperties properties) {
        this.exchange = exchange;
        this.properties = properties;
    }

    /**
     * Sigue redirecciones hasta el límite, comprueba estado y cabeceras y copia la respuesta
     * aceptada al destino local, cerrando cada respuesta utilizada.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param requestedMaxFileBytes Máximo por archivo solicitado en bytes; también se aplica el
     *     límite de configuración.
     * @return archivo completo con tamaño y SHA-256 calculados.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si falla
     *     el estado HTTP, redirección, formato, tamaño, presupuesto, interrupción o E/S local.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long requestedMaxFileBytes) {
        long maxFileBytes = Math.min(requestedMaxFileBytes, properties.maxFileSize().toBytes());
        URI current = item.url();
        for (int redirects = 0; redirects <= properties.maxRedirects(); redirects++) {
            RemoteExchange.Response response = exchange.get(current);
            if (REDIRECT_STATUSES.contains(response.statusCode())) {
                try {
                    if (redirects == properties.maxRedirects()) {
                        throw new DownloadRejectedException("too_many_redirects");
                    }
                    current = resolveRedirect(current, response);
                } finally {
                    closeQuietly(response);
                }
                continue;
            }
            try (response) {
                requireSuccessful(response);
                verifyDeclaredSize(response, maxFileBytes);
                verifyResponseMetadata(response);
                return streamToDisk(
                        item,
                        filename,
                        target,
                        response.body(),
                        totalBudget,
                        maxFileBytes);
            } catch (IOException exception) {
                throw new DownloadRejectedException("local_io_error", exception);
            }
        }
        throw new DownloadRejectedException("too_many_redirects");
    }

    /**
     * Resuelve Location respecto a la URI actual y deja la comprobación del destino al siguiente
     * intercambio.
     *
     * @param current URI de la respuesta que contiene la nueva cabecera Location.
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     * @return URI de la siguiente petición.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si falta
     *     Location o no forma una URI resoluble.
     */
    private URI resolveRedirect(URI current, RemoteExchange.Response response) {
        String location = response.headers().firstValue("location")
                .orElseThrow(() -> new DownloadRejectedException("redirect_without_location"));
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException exception) {
            throw new DownloadRejectedException("invalid_redirect", exception);
        }
    }

    /**
     * Acepta únicamente estados 2xx y conserva Retry-After cuando rechaza una respuesta remota.
     *
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     estado está fuera del intervalo 200–299.
     */
    private void requireSuccessful(RemoteExchange.Response response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DownloadRejectedException(
                    "remote_http_" + response.statusCode(), retryAfter(response));
        }
    }

    /**
     * Interpreta Retry-After como segundos o fecha HTTP y evita devolver duraciones negativas.
     *
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     * @return espera sugerida; null cuando falta la cabecera o no puede interpretarse.
     */
    private Duration retryAfter(RemoteExchange.Response response) {
        return response.headers().firstValue("retry-after").map(value -> {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
            } catch (NumberFormatException ignored) {
                try {
                    Instant requested = ZonedDateTime.parse(
                            value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Duration.between(Instant.now(), requested).isNegative()
                            ? Duration.ZERO
                            : Duration.between(Instant.now(), requested);
                } catch (RuntimeException invalid) {
                    return null;
                }
            }
        }).orElse(null);
    }

    /**
     * Rechaza Content-Length cuando anuncia más bytes que el límite del archivo.
     *
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     tamaño declarado supera el máximo.
     */
    private void verifyDeclaredSize(RemoteExchange.Response response, long maxFileBytes) {
        response.headers().firstValueAsLong("content-length").ifPresent(length -> {
            if (length > maxFileBytes) {
                throw new DownloadRejectedException("file_size_limit_exceeded");
            }
        });
    }

    /**
     * Exige codificación identity y rechaza tipos HTML o JSON para evitar entregar una página de
     * error como instalador.
     *
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si la
     *     respuesta está codificada o declara contenido HTML o JSON.
     */
    private void verifyResponseMetadata(RemoteExchange.Response response) {
        String contentEncoding = response.headers()
                .firstValue("content-encoding")
                .orElse("identity");
        if (!contentEncoding.equalsIgnoreCase("identity")) {
            throw new DownloadRejectedException("encoded_response_not_supported");
        }
        String contentType = response.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
            throw new DownloadRejectedException("unexpected_download_content_type");
        }
    }

    /**
     * Crea el destino y transfiere bloques comprobando interrupción, tamaño máximo y presupuesto
     * compartido antes de escribir y actualizar la huella.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param input Flujo de respuesta que se consume por bloques de 64 KiB.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto local con identidad y metadatos de instalación originales.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si se
     *     excede un límite, se interrumpe la tarea o falla la E/S.
     */
    private DownloadedArtifact streamToDisk(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            InputStream input,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        long fileBytes = 0;
        MessageDigest digest = sha256Digest();
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new DownloadRejectedException("download_interrupted");
                    }
                    if (read == 0) {
                        continue;
                    }
                    fileBytes += read;
                    if (fileBytes > maxFileBytes) {
                        throw new DownloadRejectedException("file_size_limit_exceeded");
                    }
                    totalBudget.consume(read);
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            throw new DownloadRejectedException("local_io_error", exception);
        } catch (RuntimeException exception) {
            // Conserva las denegaciones de reserva: requieren limpiar y volver a la cola FIFO.
            throw exception;
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new DownloadedArtifact(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                filename,
                target,
                fileBytes,
                sha256,
                null, item.installation());
    }

    /**
     * Obtiene un acumulador SHA-256 independiente para una transferencia.
     *
     * @return acumulador vacío.
     * @throws IllegalStateException si no se dispone del algoritmo SHA-256.
     */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * Cierra una respuesta descartada por redirección y registra fallos de cierre sin sustituir el
     * resultado principal.
     *
     * @param response Respuesta HTTP cuyo estado, cabeceras o cierre se procesan.
     */
    private void closeQuietly(RemoteExchange.Response response) {
        try {
            response.close();
        } catch (IOException exception) {
            LOGGER.debug("Could not close discarded HTTP response", exception);
        }
    }
}
