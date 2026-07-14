package es.ubu.batchdownloader.common;

public class RateLimitException extends RuntimeException {
    private final String code;

    public RateLimitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
