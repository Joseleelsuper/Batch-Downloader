package es.ubu.batchdownloader.translation.application;

import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implementa el componente {@code GetLocale}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class GetLocale {

    /**
     * Estado {@code catalog} mantenido por {@code GetLocale}.
     */
    private final LocaleCatalog catalog;

    /**
     * Inicializa una instancia de {@code GetLocale}.
     *
     * @param catalog Acceso al catálogo utilizado por la operación.
     */
    public GetLocale(LocaleCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Ejecuta la operación {@code execute}.
     *
     * @param locale Valor de {@code locale} utilizado por la operación.
     * @return Resultado producido por {@code execute}.
     */
    public Optional<LocaleDocument> execute(String locale) {
        return catalog.findByLocale(locale);
    }
}
