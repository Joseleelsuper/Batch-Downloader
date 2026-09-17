package es.ubu.batchdownloader.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las sondas exigen los recursos del consumidor sin depender de la disponibilidad de
 * proveedores de correo.
 *
 * @see es.ubu.batchdownloader.notification.operations.NotificationWorkerHeartbeat
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class HealthReadinessConfigurationTest {
    /**
     * Comprueba que readiness incluye base de datos, Rabbit y latido, mientras liveness conserva
     * solo señales del proceso.
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
     * Carga las propiedades de producción para comprobar las sondas sin arrancar el servicio.
     *
     * @return configuración del recurso application.properties.
     */
    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }
        return properties;
    }
}
