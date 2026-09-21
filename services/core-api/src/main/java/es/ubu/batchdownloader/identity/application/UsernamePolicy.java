package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deriva nombres iniciales del correo y aplica el formato manual y la normalización compartidos por
 * creación automática y edición.
 *
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public final class UsernamePolicy {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern INVALID = Pattern.compile("[^a-z0-9._-]+");
    private static final Pattern SEPARATORS = Pattern.compile("[._-]+");
    private static final Pattern MANUAL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]{1,38}[A-Za-z0-9])$");

    /**
     * Impide instancias de la política estática de nombres de usuario.
     */
    private UsernamePolicy() {}

    /**
     * Normaliza la parte local a ASCII, agrupa separadores y garantiza un nombre de 3–40 caracteres
     * con prefijo user cuando hace falta.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @return nombre base sin separadores extremos; el llamador resuelve su posible colisión.
     */
    public static String fromEmail(String email) {
        String local = email.substring(0, email.lastIndexOf('@'));
        String ascii = MARKS.matcher(Normalizer.normalize(local, Normalizer.Form.NFKD)).replaceAll("");
        String candidate = INVALID.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        candidate = trimSeparators(SEPARATORS.matcher(candidate).replaceAll("-"));
        if (candidate.isBlank()) candidate = "user";
        if (candidate.length() < 3) candidate = "user-" + candidate;
        return truncateAndTrim(candidate, 40);
    }

    /**
     * Recorta el nombre base a 31 caracteres y añade guion y sufijo para reservar otro nombre de
     * hasta 40.
     *
     * @param base Nombre base derivado del correo, que se acota para dejar espacio al sufijo.
     * @param suffix Sufijo aleatorio de ocho caracteres usado tras una colisión de nombre.
     * @return candidato que aún debe comprobarse contra la unicidad persistida.
     */
    public static String collisionCandidate(String base, String suffix) {
        String prefix = truncateAndTrim(base, 31);
        return prefix + "-" + suffix;
    }

    /**
     * Recorta y exige el patrón de 3–40 caracteres con extremos alfanuméricos y separadores punto,
     * guion o guion bajo internos.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return nombre visible validado, conservando mayúsculas.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el texto no cumple el patrón de
     *     nombre manual.
     */
    public static String validateManual(String value) {
        String clean = value == null ? "" : value.strip();
        if (!MANUAL.matcher(clean).matches()) {
            throw new BadRequestException(
                    "invalid_username",
                    "El username debe tener entre 3 y 40 caracteres y usar letras, números, punto, guion o guion bajo.");
        }
        return clean;
    }

    /**
     * Recorta y convierte el nombre a minúsculas con Locale.ROOT para comparar su unicidad.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return clave normalizada del nombre no nulo.
     */
    public static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Acota un candidato ASCII y retira los separadores que hayan quedado en los extremos.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @param max Máxima longitud en caracteres de la cadena ASCII antes de retirar separadores
     *     extremos.
     * @return nombre de longitud no superior al máximo.
     */
    private static String truncateAndTrim(String value, int max) {
        return trimSeparators(value.length() <= max ? value : value.substring(0, max));
    }

    /**
     * Retira puntos, guiones y guiones bajos solo al principio y al final del candidato.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return subcadena interior, posiblemente vacía.
     */
    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSeparator(value.charAt(start))) start++;
        while (end > start && isSeparator(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    /**
     * Reconoce los tres separadores permitidos dentro de un nombre.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return true para punto, guion o guion bajo.
     */
    private static boolean isSeparator(char value) {
        return value == '.' || value == '_' || value == '-';
    }
}
