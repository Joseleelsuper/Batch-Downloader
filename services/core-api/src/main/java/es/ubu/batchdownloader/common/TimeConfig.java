package es.ubu.batchdownloader.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Define la configuración utilizada por {@code TimeConfig}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
public class TimeConfig {
    /**
     * Ejecuta la operación {@code clock}.
     *
     * @return Resultado producido por {@code clock}.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
