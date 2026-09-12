package es.ubu.batchdownloader.downloadworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Verifica que disponibilidad del worker incluye sus dependencias de procesamiento y distingue la
 * salud básica del proceso.
 *
 * @see es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class HealthReadinessConfigurationTest {
    /**
     * Comprueba que readiness incluye base de datos, RabbitMQ y latido, mientras liveness solo
     * incluye estado del proceso y ping.
     */
    @Test
    void readinessRequiresDatabaseRabbitmqAndFreshHeartbeat() throws IOException {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState,ping");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,rabbit,workerHeartbeat");
    }

    /**
     * Carga desde el classpath la configuración que utilizará el worker en producción.
     *
     * @return propiedades reales de la aplicación.
     * @throws java.io.IOException si falla la lectura del recurso de configuración.
     */
    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }
        return properties;
    }
}
