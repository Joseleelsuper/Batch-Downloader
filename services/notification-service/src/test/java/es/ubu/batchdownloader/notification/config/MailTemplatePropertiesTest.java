package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

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
                .withPropertyValues("notification.mail.public-base-url=https://batch.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MailTemplateProperties.class).publicBaseUrl())
                            .isEqualTo(URI.create("https://batch.example.com"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MailTemplateProperties.class)
    static class PropertiesConfiguration {}
}
