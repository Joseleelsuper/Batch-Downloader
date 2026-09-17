package es.ubu.batchdownloader.translation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Arranca el servicio HTTP que valida y publica el catálogo de traducciones del frontend.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @see es.ubu.batchdownloader.translation.infrastructure.file.JsonFileLocaleCatalog
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Main {

    /**
     * Inicia Spring, registra las propiedades del catálogo y habilita sus controladores HTTP.
     *
     * @param args Opciones de arranque que Spring incorpora a la configuración del servicio.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
