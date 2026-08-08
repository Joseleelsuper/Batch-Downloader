package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;

/** Política compartida por registro y restablecimiento. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

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
