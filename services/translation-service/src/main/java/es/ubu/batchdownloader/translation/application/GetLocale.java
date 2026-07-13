package es.ubu.batchdownloader.translation.application;

import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GetLocale {

    private final LocaleCatalog catalog;

    public GetLocale(LocaleCatalog catalog) {
        this.catalog = catalog;
    }

    public Optional<LocaleDocument> execute(String locale) {
        return catalog.findByLocale(locale);
    }
}
