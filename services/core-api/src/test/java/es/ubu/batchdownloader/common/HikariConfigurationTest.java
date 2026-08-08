package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class HikariConfigurationTest {

    @Test
    void hikariDurationsUseNumericMilliseconds() throws IOException {
        Properties properties = loadApplicationProperties();

        assertThat(properties.getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("${CORE_API_DB_POOL_TIMEOUT}");
        for (String key : List.of(
                "spring.datasource.hikari.validation-timeout",
                "spring.datasource.hikari.max-lifetime",
                "spring.datasource.hikari.keepalive-time")) {
            assertThat(properties.getProperty(key))
                    .as(key)
                    .matches("[0-9]+");
        }
    }

    private static Properties loadApplicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = HikariConfigurationTest.class.getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        return properties;
    }
}
