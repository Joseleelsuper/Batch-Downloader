package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.identity.domain.UserAccount;

public interface DownloadEventPublisher {
    void jobRequested(DownloadJob job);
    void cancellationRequested(DownloadJob job);
    void terminalNotificationRequested(UserAccount owner, DownloadJob job);
}
