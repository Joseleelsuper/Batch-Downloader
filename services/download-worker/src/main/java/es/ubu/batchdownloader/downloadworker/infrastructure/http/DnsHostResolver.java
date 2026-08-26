package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Resuelve los recursos gestionados por {@code DnsHostResolver}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class DnsHostResolver implements HostResolver {
    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param hostname Valor de {@code hostname} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
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
