package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;

/**
 * Valida contraseñas nuevas por longitud y composición y limita las de acceso a lo que BCrypt puede
 * procesar íntegramente.
 *
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.application.port.PasswordHasher
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public final class PasswordPolicy {
    /**
     * Mínimo de ocho puntos de código Unicode para una contraseña nueva.
     */
    public static final int MINIMUM_CHARACTERS = 8;
    /**
     * Máximo de 72 bytes UTF-8 admitidos antes de calcular o comprobar BCrypt.
     */
    public static final int MAXIMUM_UTF8_BYTES = 72;

    /**
     * Impide instancias de una política formada únicamente por comprobaciones estáticas.
     */
    private PasswordPolicy() {}

    /**
     * Exige ocho puntos de código, como máximo 72 bytes UTF-8, mayúscula, minúscula, dígito y
     * carácter especial que no sea espacio.
     *
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la contraseña es nula o incumple
     *     alguna regla; el código identifica la primera detectada.
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

    /**
     * Considera especial cualquier punto de código que no sea letra, dígito ni espacio Unicode.
     *
     * @param codePoint Punto de código Unicode que se evalúa como carácter especial.
     * @return true si puede satisfacer la regla de carácter especial.
     */
    private static boolean isSpecialCharacter(int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
    }

    /**
     * Evita truncamientos de BCrypt sin exigir a contraseñas antiguas las reglas de longitud mínima
     * o composición actuales.
     *
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la contraseña es nula o supera
     *     72 bytes UTF-8.
     */
    public static void requireSupportedForLogin(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new BadRequestException(
                    "password_too_long", "La contraseña supera el máximo de 72 bytes admitido.");
        }
    }
}
