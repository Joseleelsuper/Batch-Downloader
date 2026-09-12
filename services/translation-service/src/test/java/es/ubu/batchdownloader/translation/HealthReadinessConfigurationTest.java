package es.ubu.batchdownloader.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las sondas de traducciones dependen del proceso y no de servicios externos.
 *
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
class HealthReadinessConfigurationTest {
    /**
     * Comprueba la configuración de liveness y readiness para el servicio de catálogo en memoria.
     */
    @Test
    void readinessOnlyRequiresTheApplication() throws IOException {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState,ping");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,ping");
    }

    /**
     * Lee la configuración real de sondas sin iniciar Spring.
     *
     * @return propiedades del recurso de producción.
     */
    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }
        return properties;
    }
}
