package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code FilenamePolicyTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class FilenamePolicyTest {
    /**
     * Dato compartido {@code policy} para los escenarios de prueba.
     */
    private final FilenamePolicy policy = new FilenamePolicy();

    /**
     * Comprueba el escenario {@code sanitizesTraversalAndDeduplicatesCaseInsensitively}.
     */
    @Test
    void sanitizesTraversalAndDeduplicatesCaseInsensitively() {
        Set<String> names = policy.newNameSet();
        ResolvedDownloadItem first = item("one", "../Setup.EXE");
        ResolvedDownloadItem second = item("two", "setup.exe");

        assertThat(policy.filenameFor(first, names)).isEqualTo("Setup.EXE");
        assertThat(policy.filenameFor(second, names)).isEqualTo("setup-2.exe");
    }

    /**
     * Comprueba el escenario {@code preservesCompoundTarGzExtensionWhenAddingSuffix}.
     */
    @Test
    void preservesCompoundTarGzExtensionWhenAddingSuffix() {
        Set<String> names = policy.newNameSet();
        assertThat(policy.filenameFor(item("one", "tool.tar.gz"), names)).isEqualTo("tool.tar.gz");
        assertThat(policy.filenameFor(item("two", "tool.tar.gz"), names)).isEqualTo("tool-2.tar.gz");
    }

    /**
     * Comprueba el escenario {@code sanitizesAndDeduplicatesManualShortcutNames}.
     */
    @Test
    void sanitizesAndDeduplicatesManualShortcutNames() {
        Set<String> names = policy.newNameSet();

        assertThat(policy.manualShortcutFilename("../CON", names)).isEqualTo("_CON.url");
        assertThat(policy.manualShortcutFilename("Mi/App", names)).isEqualTo("Mi-App.url");
        assertThat(policy.manualShortcutFilename("mi-app", names)).isEqualTo("mi-app-2.url");
    }

    /**
     * Ejecuta la operación {@code item}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @return Resultado producido por {@code item}.
     */
    private ResolvedDownloadItem item(String id, String filename) {
        return new ResolvedDownloadItem(
                UUID.nameUUIDFromBytes(("item-" + id).getBytes()),
                UUID.nameUUIDFromBytes(("app-" + id).getBytes()),
                UUID.nameUUIDFromBytes(("source-" + id).getBytes()),
                URI.create("https://downloads.example.com/" + filename.replace("../", "")),
                filename,
                "windows",
                "x86_64",
                null,
                null,
                null);
    }
}
