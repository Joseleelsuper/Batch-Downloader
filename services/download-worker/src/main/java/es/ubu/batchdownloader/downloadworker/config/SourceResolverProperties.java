package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configura la conexión interna con el scraper para revalidar la fuente exacta de cada elemento
 * antes de descargarlo.
 *
 * @param baseUrl URL base del servicio interno al que se añaden rutas versionadas.
 * @param serviceToken Credencial de llamadas internas; un valor vacío no concede acceso al servicio
 *     destino.
 * @param timeout Duración máxima de la petición interna.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpSourceReferenceResolver
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
 */
@Validated
@ConfigurationProperties("download-worker.source-resolver")
public record SourceResolverProperties(
        @DefaultValue("http://scraper-api:8000") @NotBlank String baseUrl,
        @DefaultValue("") String serviceToken,
        @DefaultValue("10s") @NotNull Duration timeout) {
}
