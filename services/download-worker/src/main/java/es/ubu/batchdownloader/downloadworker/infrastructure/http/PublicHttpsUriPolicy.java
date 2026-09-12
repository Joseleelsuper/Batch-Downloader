package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * Implementa el puerto de URI pública exigiendo HTTPS sin credenciales y rechazando un host si
 * cualquiera de sus direcciones incumple las exclusiones de red configuradas en el código.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.PublicUriPolicy
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.HostResolver
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsRemoteExchange
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public class PublicHttpsUriPolicy implements PublicUriPolicy {
    /**
     * Estado {@code hostResolver} mantenido por {@code PublicHttpsUriPolicy}.
     */
    private final HostResolver hostResolver;

    /**
     * Conecta la consulta DNS usada para comprobar todas las direcciones del destino.
     *
     * @param hostResolver Consulta DNS utilizada para comprobar todas las direcciones del host.
     */
    public PublicHttpsUriPolicy(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    /**
     * Exige HTTPS, host presente y ausencia de userinfo y comprueba que DNS produce direcciones y
     * que todas superan la política de acceso público.
     *
     * @param uri URI de destino que debe superar la política de acceso a recursos públicos.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     esquema, autoridad, resolución DNS o alguna dirección no son admisibles.
     */
    @Override
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
     * Aplica las exclusiones locales, privadas, multicast y rangos especiales IPv4 e IPv6 antes de
     * admitir un destino.
     *
     * @param address Dirección IP que se compara con las exclusiones de la política pública.
     * @return true si la dirección supera todas las exclusiones implementadas.
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
