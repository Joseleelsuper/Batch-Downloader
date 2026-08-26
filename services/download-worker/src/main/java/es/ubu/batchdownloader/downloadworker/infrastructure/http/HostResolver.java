package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import java.net.InetAddress;
import java.util.List;

/**
 * Define el contrato de {@code HostResolver}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@FunctionalInterface
public interface HostResolver {
    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param hostname Valor de {@code hostname} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    List<InetAddress> resolve(String hostname);
}
