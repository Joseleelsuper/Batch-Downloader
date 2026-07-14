package es.ubu.batchdownloader.downloads.application.port;

import java.net.URI;
import java.time.Duration;

public interface ZipUriSigner {
    URI signGet(String objectKey, Duration validity);
}
