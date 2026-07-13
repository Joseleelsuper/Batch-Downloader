package es.ubu.batchdownloader.downloadworker.application;

public class DownloadRejectedException extends RuntimeException {
    private final String code;

    public DownloadRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public DownloadRejectedException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
