package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * Implementa el componente {@code PublicHttpsUriPolicy}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class PublicHttpsUriPolicy {
    /**
     * Estado {@code hostResolver} mantenido por {@code PublicHttpsUriPolicy}.
     */
    private final HostResolver hostResolver;

    /**
     * Inicializa una instancia de {@code PublicHttpsUriPolicy}.
     *
     * @param hostResolver Valor de {@code hostResolver} utilizado por la operación.
     */
    public PublicHttpsUriPolicy(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    /**
     * Valida los datos recibidos mediante {@code validate}.
     *
     * @param uri Valor de {@code uri} utilizado por la operación.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public void validate(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DownloadRejectedException("https_required");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new DownloadRejectedException("invalid_download_uri");
        }
        List<InetAddress> addresses = hostResolver.resolve(uri.getHost());
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
            throw new DownloadRejectedException("non_public_download_host");
        }
    }

    /**
     * Indica si se cumple la condición mediante {@code isPublic}.
     *
     * @param address Valor de {@code address} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && (third == 0 || third == 2))
                    && !(first == 198 && (second == 18 || second == 19 || second == 51))
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xFE) == 0xFC;
            boolean documentation = first == 0x20
                    && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D
                    && Byte.toUnsignedInt(bytes[3]) == 0xB8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }
}
