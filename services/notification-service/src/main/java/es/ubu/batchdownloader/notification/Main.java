package es.ubu.batchdownloader.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Arranca el consumidor de correo, registra sus propiedades y habilita las tareas periódicas de
 * mantenimiento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.persistence.NotificationInboxRetentionPruner
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Main {

    /**
     * Inicia el contexto Spring que conecta el consumidor Rabbit con sus proveedores y
     * almacenamiento.
     *
     * @param args Opciones de arranque que Spring incorpora a la configuración del servicio.
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
