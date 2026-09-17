package es.ubu.batchdownloader.downloadworker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Verifica el formato de timeout que Hikari puede enlazar desde las propiedades del worker.
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class HikariConfigurationTest {

    /**
     * Carga las propiedades reales y exige números de milisegundos para timeout de conexión y
     * validación del pool.
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
