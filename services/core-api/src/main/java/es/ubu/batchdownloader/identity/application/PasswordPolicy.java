package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Valida la contraseña en los casos de uso de registro y restablecimiento. */
public final class PasswordPolicy {
    public static final int MINIMUM_CHARACTERS = 14;
    public static final int MAXIMUM_UTF8_BYTES = 72;
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "abc123", "admin", "administrator",
            "baseball", "dragon", "football", "iloveyou", "letmein", "login", "master",
            "monkey", "password", "password1", "password123", "password1234", "password123456",
            "passw0rd", "princess", "qwerty", "qwerty123", "qwertyuiop", "sunshine", "welcome");

    private PasswordPolicy() {}

    /**
     * Rechaza contraseñas que Bcrypt no pueda procesar de forma íntegra.
     *
     * @param password contraseña recibida por el caso de uso
     */
    public static void requireValid(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MINIMUM_CHARACTERS) {
            throw new BadRequestException(
                    "password_too_short", "La contraseña debe contener al menos 14 caracteres.");
        }
        requireSupportedForLogin(password);
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException(
                    "password_too_common", "Elige una contraseña distinta: esta es demasiado común o predecible.");
        }
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
