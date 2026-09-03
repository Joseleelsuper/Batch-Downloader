package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;

/** Valida la contraseña en los casos de uso de registro y restablecimiento. */
public final class PasswordPolicy {
    public static final int MINIMUM_CHARACTERS = 8;
    public static final int MAXIMUM_UTF8_BYTES = 72;

    private PasswordPolicy() {}

    /**
     * Rechaza contraseñas que Bcrypt no pueda procesar de forma íntegra.
     *
     * @param password contraseña recibida por el caso de uso
     */
    public static void requireValid(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MINIMUM_CHARACTERS) {
            throw new BadRequestException(
                    "password_too_short", "La contraseña debe contener al menos 8 caracteres.");
        }
        requireSupportedForLogin(password);
        if (password.codePoints().noneMatch(Character::isUpperCase)) {
            throw new BadRequestException(
                    "password_missing_uppercase", "La contraseña debe incluir al menos una letra mayúscula.");
        }
        if (password.codePoints().noneMatch(Character::isLowerCase)) {
            throw new BadRequestException(
                    "password_missing_lowercase", "La contraseña debe incluir al menos una letra minúscula.");
        }
        if (password.codePoints().noneMatch(Character::isDigit)) {
            throw new BadRequestException(
                    "password_missing_number", "La contraseña debe incluir al menos un número.");
        }
        if (password.codePoints().noneMatch(PasswordPolicy::isSpecialCharacter)) {
            throw new BadRequestException(
                    "password_missing_special", "La contraseña debe incluir al menos un carácter especial.");
        }
    }

    private static boolean isSpecialCharacter(int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
    }

    /**
     * Protege el verificador BCrypt sin impedir que cuentas antiguas inicien sesión para actualizar su clave.
     *
     * @param password contraseña presentada durante la autenticación
     */
    public static void requireSupportedForLogin(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new BadRequestException(
                    "password_too_long", "La contraseña supera el máximo de 72 bytes admitido.");
        }
    }
}
