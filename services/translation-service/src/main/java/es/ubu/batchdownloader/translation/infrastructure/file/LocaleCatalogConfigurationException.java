package es.ubu.batchdownloader.translation.infrastructure.file;

public class LocaleCatalogConfigurationException extends RuntimeException {

    public LocaleCatalogConfigurationException(String message) {
        super(message);
    }

    public LocaleCatalogConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
