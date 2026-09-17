package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Consulta las direcciones del host mediante el resolutor del JDK y traduce un nombre desconocido a
 * rechazo de descarga.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.HostResolver
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public class DnsHostResolver implements HostResolver {
    /**
     * Resuelve todas las direcciones que devuelve InetAddress para el nombre indicado.
     *
     * @param hostname Nombre DNS que se resuelve para validar todas sus direcciones.
     * @return direcciones que deben superar la política pública.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     host no puede resolverse, con código dns_resolution_failed.
     */
    @Override
    public List<InetAddress> resolve(String hostname) {
        try {
            return Arrays.asList(InetAddress.getAllByName(hostname));
        } catch (UnknownHostException exception) {
            throw new DownloadRejectedException("dns_resolution_failed", exception);
        }
    }
}
