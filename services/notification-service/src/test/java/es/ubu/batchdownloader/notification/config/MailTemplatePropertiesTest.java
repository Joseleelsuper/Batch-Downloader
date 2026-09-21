package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class MailTemplatePropertiesTest {
    @Test
    void requiresHttpsLogoWhenPublicWebIsHttps() {
        assertThatThrownBy(() -> new MailTemplateProperties(
                URI.create("https://batch.example.com"),
                URI.create("http://batch.example.com/assets/logo.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification.mail.logo-url debe usar HTTPS");
    }
}
