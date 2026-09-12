package es.ubu.batchdownloader.translation.application;

import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Consulta un catálogo por idioma sin acoplar el controlador al origen de sus archivos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.application.port.LocaleCatalog
 * @see es.ubu.batchdownloader.translation.infrastructure.web.LocaleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@Service
public class GetLocale {

    /**
     * Estado {@code catalog} mantenido por {@code GetLocale}.
     */
    private final LocaleCatalog catalog;

    /**
     * Asocia el caso de consulta al catálogo validado disponible para el servicio.
     *
     * @param catalog Puerto de consulta del catálogo de traducciones validado.
     */
    public GetLocale(LocaleCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Busca el documento del idioma indicado sin crear ni combinar traducciones durante la
     * petición.
     *
     * @param locale Código exacto del idioma solicitado; el catálogo actual publica es.
     * @return documento del idioma, o Optional vacío si no está publicado.
     */
    public Optional<LocaleDocument> execute(String locale) {
        return catalog.findByLocale(locale);
    }
}
