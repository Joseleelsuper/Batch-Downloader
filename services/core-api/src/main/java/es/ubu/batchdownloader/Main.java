package es.ubu.batchdownloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Arranca el proceso Spring de Core que expone catálogo, identidad, administración y descargas y
 * compone persistencia y mensajería.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogController
 * @see es.ubu.batchdownloader.messaging.MessagingConfig
 * @since 0.1.0
 * @version 0.1.0
 * @category Arranque de Core
 */
@SpringBootApplication
public class Main {

    /**
     * Inicia el contexto de Core aplicando la configuración y los argumentos de arranque recibidos.
     *
     * @param args Argumentos de arranque que Spring utiliza para configurar el proceso.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
