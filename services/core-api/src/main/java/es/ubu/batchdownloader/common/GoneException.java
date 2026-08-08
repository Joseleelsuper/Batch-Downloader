package es.ubu.batchdownloader.common;

/** Recurso que existió pero ya no puede utilizarse. */
public class GoneException extends RuntimeException {
    private final String code;

    public GoneException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
