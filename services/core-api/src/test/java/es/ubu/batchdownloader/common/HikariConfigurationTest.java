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

        for (String key : List.of(
                "spring.datasource.hikari.connection-timeout",
                "spring.datasource.hikari.validation-timeout",
                "spring.datasource.hikari.max-lifetime",
                "spring.datasource.hikari.keepalive-time")) {
            assertThat(placeholderDefault(properties.getProperty(key)))
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

    private static String placeholderDefault(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            return value.substring(value.indexOf(':') + 1, value.length() - 1);
        }
        return value;
    }
}
