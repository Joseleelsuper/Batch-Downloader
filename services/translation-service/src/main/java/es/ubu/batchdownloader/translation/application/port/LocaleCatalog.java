package es.ubu.batchdownloader.translation.application.port;

import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;

/**
 * Define el contrato de {@code LocaleCatalog}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface LocaleCatalog {

    /**
     * Busca el resultado solicitado mediante {@code findByLocale}.
     *
     * @param locale Valor de {@code locale} utilizado por la operación.
     * @return Resultado producido por {@code findByLocale}.
     */
    Optional<LocaleDocument> findByLocale(String locale);
}
