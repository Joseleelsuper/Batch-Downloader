package es.ubu.batchdownloader.downloads.infrastructure.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Construye el cliente de acceso interno al almacén usado para limpiar artefactos de descarga.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.infrastructure.storage.DownloadDeliveryService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Configuration
class MinioConfig {
    /**
     * Configura el origen y las credenciales de MinIO sin realizar una operación de red.
     *
     * @param endpoint Dirección del almacén alcanzable desde Core.
     * @param accessKey Identificador de la credencial del almacén de objetos.
     * @param secretKey Secreto de la credencial del almacén; no debe aparecer en respuestas ni
     *     registros.
     * @return cliente para operaciones internas de almacenamiento.
     */
    @Bean
    MinioClient minioClient(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
