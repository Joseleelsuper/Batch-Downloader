package es.ubu.batchdownloader.downloads.infrastructure.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Define la configuración utilizada por {@code MinioConfig}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
class MinioConfig {
    /**
     * Ejecuta la operación {@code minioClient}.
     *
     * @param endpoint Valor de {@code endpoint} utilizado por la operación.
     * @param accessKey Valor de {@code accessKey} utilizado por la operación.
     * @param secretKey Valor de {@code secretKey} utilizado por la operación.
     * @return Resultado producido por {@code minioClient}.
     */
    @Bean
    MinioClient minioClient(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
