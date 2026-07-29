package es.ubu.batchdownloader.common;

public class UnauthorizedException extends RuntimeException {
    private final String code;

    public UnauthorizedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
