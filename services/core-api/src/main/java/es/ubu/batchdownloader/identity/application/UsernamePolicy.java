package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalización y validación única de nombres de usuario. */
public final class UsernamePolicy {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern INVALID = Pattern.compile("[^a-z0-9._-]+");
    private static final Pattern SEPARATORS = Pattern.compile("[._-]+");
    private static final Pattern MANUAL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]{1,38}[A-Za-z0-9])$");

    private UsernamePolicy() {}

    public static String fromEmail(String email) {
        String local = email.substring(0, email.lastIndexOf('@'));
        String ascii = MARKS.matcher(Normalizer.normalize(local, Normalizer.Form.NFKD)).replaceAll("");
        String candidate = INVALID.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        candidate = trimSeparators(SEPARATORS.matcher(candidate).replaceAll("-"));
        if (candidate.isBlank()) candidate = "user";
        if (candidate.length() < 3) candidate = "user-" + candidate;
        return truncateAndTrim(candidate, 40);
    }

    public static String collisionCandidate(String base, String suffix) {
        String prefix = truncateAndTrim(base, 31);
        return prefix + "-" + suffix;
    }

    public static String validateManual(String value) {
        String clean = value == null ? "" : value.strip();
        if (!MANUAL.matcher(clean).matches()) {
            throw new BadRequestException(
                    "invalid_username",
                    "El username debe tener entre 3 y 40 caracteres y usar letras, números, punto, guion o guion bajo.");
        }
        return clean;
    }

    public static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String truncateAndTrim(String value, int max) {
        return trimSeparators(value.length() <= max ? value : value.substring(0, max));
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSeparator(value.charAt(start))) start++;
        while (end > start && isSeparator(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isSeparator(char value) {
        return value == '.' || value == '_' || value == '-';
    }
}
