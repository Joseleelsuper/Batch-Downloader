package es.ubu.batchdownloader.downloadworker.ports;

import java.nio.file.Path;

public interface ArtifactStore {
    void put(String objectKey, Path source, String contentType);
}
