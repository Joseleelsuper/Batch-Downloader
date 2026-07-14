package es.ubu.batchdownloader.downloadworker.domain;

public final class EventTypes {
    public static final int CURRENT_VERSION = 1;
    public static final String JOB_REQUESTED = "download.job.requested";
    public static final String JOB_CANCEL_REQUESTED = "download.job.cancel-requested";
    public static final String JOB_PROGRESSED = "download.job.progressed";
    public static final String JOB_READY = "download.job.ready";
    public static final String JOB_FAILED = "download.job.failed";

    public static final String JOB_PROGRESSED_ROUTING_KEY = "download.job.progressed";
    public static final String JOB_READY_ROUTING_KEY = "download.job.ready";
    public static final String JOB_FAILED_ROUTING_KEY = "download.job.failed";

    private EventTypes() {
    }
}
