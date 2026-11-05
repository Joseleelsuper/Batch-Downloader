package es.ubu.batchdownloader.softwarerequest;

import es.ubu.batchdownloader.common.BadRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Validación sintáctica sin DNS ni HTTP: guardar una propuesta nunca descarga su URL. */
record SoftwareRequestInput(String name, String normalizedName, String officialUrl, byte[] urlKey) {
    private static final Set<String> PRIVATE_SUFFIXES = Set.of(
            "localhost", "local", "internal", "home", "lan", "onion", "test", "invalid", "example");

    static SoftwareRequestInput parse(String rawName, String rawUrl) {
        String name = Normalizer.normalize(rawName == null ? "" : rawName, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 160 || name.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("invalid_software_request_name", "Indica un nombre de hasta 160 caracteres.");
        }
        String canonical;
        try {
            String value = rawUrl == null ? "" : rawUrl.strip();
            if (value.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(value).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !host.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
                    || PRIVATE_SUFFIXES.stream().anyMatch(suffix -> hostMatches(uri.getHost(), suffix))) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            canonical = URI.create("https://" + host + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).toASCIIString();
            if (canonical.length() > 2048) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("invalid_software_request_url", "Indica una página HTTPS con dominio público, sin credenciales ni puertos alternativos.");
        }
        try {
            return new SoftwareRequestInput(name, name.toLowerCase(Locale.ROOT), canonical,
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hostMatches(String rawHost, String suffix) {
        String host = rawHost.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        return host.equals(suffix) || host.endsWith("." + suffix);
    }
}
