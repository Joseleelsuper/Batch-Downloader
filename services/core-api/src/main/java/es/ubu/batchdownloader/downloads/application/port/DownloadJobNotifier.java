package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;

public interface DownloadJobNotifier {
    void changed(DownloadJobView job);
}
