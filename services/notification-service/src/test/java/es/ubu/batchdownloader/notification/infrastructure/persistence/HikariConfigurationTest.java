package es.ubu.batchdownloader.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Comprueba las unidades numéricas que el pool JDBC exige al interpretar tiempos de espera.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class HikariConfigurationTest {

    /**
     * Comprueba que los tiempos Hikari se expresan en milisegundos numéricos y no en duraciones
     * ISO.
     */
    @Test
    void hikariTimeoutsUseNumericMilliseconds() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = HikariConfigurationTest.class.getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        for (String key : List.of(
                "spring.datasource.hikari.connection-timeout",
                "spring.datasource.hikari.validation-timeout")) {
            assertThat(properties.getProperty(key)).as(key).matches("[0-9]+");
        }
    }
}
