package es.ubu.batchdownloader.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
