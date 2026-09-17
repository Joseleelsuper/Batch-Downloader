package es.ubu.batchdownloader.translation.infrastructure.file;

import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Cuenta aciertos y ausencias de traducciones sin modificar el resultado del catálogo de archivos.
 *
 * @see es.ubu.batchdownloader.translation.application.port.LocaleCatalog
 * @see es.ubu.batchdownloader.translation.infrastructure.file.JsonFileLocaleCatalog
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
@Component
@Primary
public final class MeteredLocaleCatalog implements LocaleCatalog {
    private final JsonFileLocaleCatalog delegate;
    private final Optional<MeterRegistry> registry;

    /**
     * Asocia el catálogo precargado con un registro opcional de métricas de consulta.
     *
     * @param delegate Catálogo de archivos que conserva los documentos validados en memoria.
     * @param registry Registro opcional de métricas de aciertos y ausencias por consulta.
     */
    public MeteredLocaleCatalog(
            JsonFileLocaleCatalog delegate,
            Optional<MeterRegistry> registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Consulta el idioma y registra hit o miss cuando existe instrumentación.
     *
     * @param locale Código exacto del idioma solicitado; el catálogo actual publica es.
     * @return el mismo documento o ausencia devueltos por el catálogo de archivos.
     */
    @Override
    public Optional<LocaleDocument> findByLocale(String locale) {
        Optional<LocaleDocument> result = delegate.findByLocale(locale);
        registry.ifPresent(meterRegistry -> meterRegistry.counter(
                                "translation_locale_lookup",
                                "outcome",
                                result.isPresent() ? "hit" : "miss")
                        .increment());
        return result;
    }
}
