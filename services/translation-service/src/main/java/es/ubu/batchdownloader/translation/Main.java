package es.ubu.batchdownloader.translation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Implementa el componente {@code Main}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Main {

    /**
     * Ejecuta el punto de entrada del servicio.
     *
     * @param args Valor de {@code args} utilizado por la operación.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
