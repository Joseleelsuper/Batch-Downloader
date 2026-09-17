package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import java.net.InetAddress;
import java.util.List;

/**
 * Permite comprobar las direcciones DNS de un host antes de admitir una URI de descarga.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
@FunctionalInterface
public interface HostResolver {
    /**
     * Obtiene todas las direcciones disponibles para evaluar si el host cumple la política pública.
     *
     * @param hostname Nombre DNS que se resuelve para validar todas sus direcciones.
     * @return direcciones resueltas del nombre solicitado.
     */
    List<InetAddress> resolve(String hostname);
}
