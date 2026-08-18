package es.ubu.batchdownloader.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Verifica que SMTP y Resend no bloqueen la vida del consumidor. */
class HealthReadinessConfigurationTest {
    @Test
    void readinessRequiresInboxAndRabbitmq() throws IOException {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState,ping");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,rabbit");
    }

    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            properties.load(stream);
        }
        return properties;
    }
}
