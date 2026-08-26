package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;

/** Valida la contraseña en los casos de uso de registro y restablecimiento. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    /**
     * Rechaza contraseñas que Bcrypt no pueda procesar de forma íntegra.
     *
     * @param password contraseña recibida por el caso de uso
     */
    public static void requireValid(String password) {
        if (password == null || password.length() < 12) {
            throw new BadRequestException(
                    "password_too_short", "La contraseña debe contener al menos 12 caracteres.");
        }
        if (password.length() > 128 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException(
                    "password_too_long", "La contraseña supera el tamaño máximo admitido.");
        }
    }
}
