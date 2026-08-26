package es.ubu.batchdownloader.downloadworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Implementa el componente {@code Main}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@SpringBootApplication
@EnableScheduling
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
