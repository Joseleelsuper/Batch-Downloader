package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Comprueba rechazo de HTTP y de respuestas DNS que incluyen direcciones privadas o rangos
 * reservados.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de archivo y transporte
 */
class PublicHttpsUriPolicyTest {
    /**
     * Fija una respuesta DNS pública y comprueba aceptación de HTTPS y rechazo de HTTP con
     * https_required.
     */
    @Test
    void acceptsOnlyHttpsWithEntirelyPublicDnsAnswers() {
        PublicHttpsUriPolicy publicPolicy = new PublicHttpsUriPolicy(
                host -> List.of(address("8.8.8.8")));

        assertThatCode(() -> publicPolicy.validate(URI.create("https://downloads.example.com/app.exe")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> publicPolicy.validate(URI.create("http://downloads.example.com/app.exe")))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("https_required");
    }

    /**
     * Mezcla una dirección pública con loopback y exige el rechazo completo del host con
     * non_public_download_host.
     */
    @Test
    void rejectsAHostWhenAnyDnsAnswerIsPrivate() {
        PublicHttpsUriPolicy policy = new PublicHttpsUriPolicy(host -> List.of(
                address("8.8.8.8"),
                address("127.0.0.1")));

        assertThatThrownBy(() -> policy.validate(URI.create("https://downloads.example.com/app.exe")))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("non_public_download_host");
    }

    /**
     * Comprueba que las direcciones de documentación 192.0.2.1 y de CGNAT 100.64.0.1 no se
     * clasifican como públicas.
     */
    @Test
    void classifiesReservedRangesAsNonPublic() throws Exception {
        assertThatCode(() -> {
            if (PublicHttpsUriPolicy.isPublic(InetAddress.getByName("192.0.2.1"))) {
                throw new AssertionError("documentation address was accepted");
            }
            if (PublicHttpsUriPolicy.isPublic(InetAddress.getByName("100.64.0.1"))) {
                throw new AssertionError("carrier-grade NAT address was accepted");
            }
        }).doesNotThrowAnyException();
    }

    /**
     * Convierte un literal IP de prueba en la respuesta del doble DNS.
     *
     * @param value literal de dirección IP del escenario.
     * @return dirección indicada por el escenario.
     * @throws java.lang.IllegalArgumentException si el literal no puede convertirse en dirección
     *     IP.
     */
    private InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
