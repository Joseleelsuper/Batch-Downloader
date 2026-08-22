package es.ubu.batchdownloader.translation.infrastructure.file;

import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Añade observabilidad al catálogo en memoria sin introducir I/O ni fallback por petición. */
@Component
@Primary
public final class MeteredLocaleCatalog implements LocaleCatalog {
    private final JsonFileLocaleCatalog delegate;
    private final Optional<MeterRegistry> registry;

    /** Inicializa el wrapper sobre el único catálogo funcional. */
    public MeteredLocaleCatalog(
            JsonFileLocaleCatalog delegate,
            Optional<MeterRegistry> registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** {@inheritDoc} */
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
