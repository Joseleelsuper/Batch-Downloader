package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code FernetUrlProtectorTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class FernetUrlProtectorTest {
    /**
     * Comprueba el escenario {@code revealsProtectedUrlAndRejectsInvalidTokens}.
     */
    @Test
    void revealsProtectedUrlAndRejectsInvalidTokens() {
        FernetUrlProtector protector = new FernetUrlProtector("test-secret");
        String url = "https://example.com/installer.exe";

        String encrypted = protector.protect(url);

        assertThat(protector.reveal(encrypted)).isEqualTo(url);
        assertThat(protector.reveal("not-a-fernet-token")).isNull();
    }
}
