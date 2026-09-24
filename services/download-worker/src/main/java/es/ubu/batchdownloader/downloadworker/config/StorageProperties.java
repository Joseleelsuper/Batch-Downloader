package es.ubu.batchdownloader.downloadworker.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

/**
 * Agrupa acceso al almacén, vigencia de resultados y cuota lógica utilizados por la publicación y
 * admisión de artefactos.
 *
 * @param endpoint URL interna del almacenamiento compatible con S3.
 * @param accessKey Identificador de la credencial de acceso al almacén.
 * @param secretKey Secreto de autenticación del almacén; no debe incluirse en eventos ni
 *     manifiestos.
 * @param bucket Contenedor lógico de los artefactos del worker.
 * @param presignedUrlTtl Vigencia configurada de los resultados; el emisor la acota a siete días.
 * @param quota Límite lógico de objetos persistidos más reservas en vuelo.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.storage.MinioArtifactStore
 * @see es.ubu.batchdownloader.downloadworker.application.JobStorageReservation
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
 */
@Validated
@ConfigurationProperties("download-worker.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") @NotBlank String endpoint,
        @DefaultValue("minioadmin") @NotBlank String accessKey,
        @DefaultValue("minioadmin") @NotBlank String secretKey,
        @DefaultValue("installers") @NotBlank String bucket,
        @DefaultValue("6h") @NotNull Duration presignedUrlTtl,
        @DefaultValue("120GB") @NotNull DataSize quota) {

    /**
     * Conserva el constructor abreviado aplicando una cuota de 120 GiB.
     *
     * @param endpoint URL interna del almacenamiento compatible con S3.
     * @param accessKey Identificador de la credencial de acceso al almacén.
     * @param secretKey Secreto de autenticación del almacén; no debe incluirse en eventos ni
     *     manifiestos.
     * @param bucket Contenedor lógico de los artefactos del worker.
     * @param presignedUrlTtl Vigencia configurada de los resultados; el emisor la acota a siete
     *     días.
     */
    public StorageProperties(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            Duration presignedUrlTtl) {
        this(endpoint, accessKey, secretKey, bucket, presignedUrlTtl, DataSize.ofGigabytes(120));
    }

    /**
     * Designa el constructor canónico para enlazar todas las propiedades del almacén desde Spring.
     *
     * @param endpoint URL interna del almacenamiento compatible con S3.
     * @param accessKey Identificador de la credencial de acceso al almacén.
     * @param secretKey Secreto de autenticación del almacén; no debe incluirse en eventos ni
     *     manifiestos.
     * @param bucket Contenedor lógico de los artefactos del worker.
     * @param presignedUrlTtl Vigencia configurada de los resultados; el emisor la acota a siete
     *     días.
     * @param quota Límite lógico de objetos persistidos más reservas en vuelo.
     */
    @ConstructorBinding
    public StorageProperties {}
}
