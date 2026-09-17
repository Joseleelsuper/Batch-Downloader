package es.ubu.batchdownloader.downloadworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Arranca el proceso Spring del worker de descargas y habilita las tareas periódicas de salud,
 * capacidad y retención.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.config.WorkerConfiguration
 * @see es.ubu.batchdownloader.downloadworker.config.RabbitTopologyConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
@SpringBootApplication
@EnableScheduling
public class Main {

    /**
     * Inicia el contexto del worker con los argumentos recibidos y su configuración de servicios y
     * consumidores.
     *
     * @param args Argumentos de arranque que Spring utiliza para configurar el proceso.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
