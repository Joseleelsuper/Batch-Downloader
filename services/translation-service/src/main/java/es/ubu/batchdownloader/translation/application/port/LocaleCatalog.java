package es.ubu.batchdownloader.translation.application.port;

import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;

/**
 * Permite consultar documentos de traducción ya validados junto con su identificador de caché.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.domain.LocaleDocument
 * @see es.ubu.batchdownloader.translation.application.GetLocale
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
public interface LocaleCatalog {

    /**
     * Consulta el catálogo por su código exacto de idioma.
     *
     * @param locale Código exacto del idioma solicitado; el catálogo actual publica es.
     * @return documento disponible, o Optional vacío si el idioma no está publicado.
     */
    Optional<LocaleDocument> findByLocale(String locale);
}
