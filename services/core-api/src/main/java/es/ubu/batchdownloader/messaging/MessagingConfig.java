package es.ubu.batchdownloader.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class MessagingConfig {
    @Bean
    TopicExchange batchDownloaderExchange(@Value("${app.messaging.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }
}
