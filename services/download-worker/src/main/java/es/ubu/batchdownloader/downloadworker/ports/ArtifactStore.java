package es.ubu.batchdownloader.downloadworker.ports;

import java.nio.file.Path;

public interface ArtifactStore {
    void put(String objectKey, Path source, String contentType);

    /** Staging copies are short-lived and must not accumulate after the ZIP is produced. */
    default void delete(String objectKey) {
        // A no-op keeps lightweight test doubles focused on the objects they expose.
    }
}
