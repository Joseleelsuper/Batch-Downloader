package es.ubu.batchdownloader.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proporciona un reloj UTC común que los casos de uso pueden sustituir por uno determinista en
 * pruebas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
@Configuration
public class TimeConfig {
    /**
     * Selecciona la hora del sistema expresada en UTC para fechas y vencimientos del dominio.
     *
     * @return reloj UTC compartido por el contexto de Spring.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
