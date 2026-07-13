package es.ubu.batchdownloader.translation.application.port;

import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.util.Optional;

public interface LocaleCatalog {

    Optional<LocaleDocument> findByLocale(String locale);
}
