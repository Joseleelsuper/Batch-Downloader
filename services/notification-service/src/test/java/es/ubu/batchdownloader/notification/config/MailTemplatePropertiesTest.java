package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MailTemplatePropertiesTest {
    @Test
    void bindsTheCanonicalConstructorWhenSpringCreatesThePropertiesBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "notification.mail.public-base-url=https://batch.example.com",
                        "notification.mail.logo-url=https://cdn.example.com/logo.png")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MailTemplateProperties.class).logoUrl())
                            .isEqualTo(URI.create("https://cdn.example.com/logo.png"));
                });
    }

    @Test
    void requiresHttpsLogoWhenPublicWebIsHttps() {
        assertThatThrownBy(() -> new MailTemplateProperties(
                URI.create("https://batch.example.com"),
                URI.create("http://batch.example.com/assets/logo.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification.mail.logo-url debe usar HTTPS");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MailTemplateProperties.class)
    static class PropertiesConfiguration {}
}
