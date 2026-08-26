package es.ubu.batchdownloader.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Verifica que traducciones publique probes independientes de servicios externos. */
class HealthReadinessConfigurationTest {
    @Test
    void readinessOnlyRequiresTheApplication() throws IOException {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState,ping");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,ping");
    }

    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }
        return properties;
    }
}
