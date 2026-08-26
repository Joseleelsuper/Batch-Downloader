package es.ubu.batchdownloader.downloadworker.infrastructure;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Implementa el componente {@code Hashing}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class Hashing {
    /**
     * Inicializa una instancia de {@code Hashing}.
     */
    private Hashing() {
    }

    /**
     * Ejecuta la operación {@code sha256}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @return Resultado producido por {@code sha256}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new InfrastructureException("sha256_failed", exception);
        }
    }
}
