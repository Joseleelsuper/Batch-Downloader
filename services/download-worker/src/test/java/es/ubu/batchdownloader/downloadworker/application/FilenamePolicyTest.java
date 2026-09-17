package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Comprueba compatibilidad de nombres, protección frente a rutas y colisiones sin distinguir
 * mayúsculas, conservando extensiones compuestas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.FilenamePolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class FilenamePolicyTest {
    /**
     * Dato compartido {@code policy} para los escenarios de prueba.
     */
    private final FilenamePolicy policy = new FilenamePolicy();

    /**
     * Propone una ruta con ../ y otro nombre equivalente en minúsculas y comprueba que se elimina
     * el recorrido y se añade -2 al duplicado.
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
     * Propone dos nombres tar.gz iguales y comprueba que el sufijo de deduplicación se añade antes
     * de la extensión completa.
     */
    @Test
    void preservesCompoundTarGzExtensionWhenAddingSuffix() {
        Set<String> names = policy.newNameSet();
        assertThat(policy.filenameFor(item("one", "tool.tar.gz"), names)).isEqualTo("tool.tar.gz");
        assertThat(policy.filenameFor(item("two", "tool.tar.gz"), names)).isEqualTo("tool-2.tar.gz");
    }

    /**
     * Comprueba el prefijo de nombres reservados de Windows, la retirada de separadores y la
     * deduplicación de accesos .url.
     */
    @Test
    void sanitizesAndDeduplicatesManualShortcutNames() {
        Set<String> names = policy.newNameSet();

        assertThat(policy.manualShortcutFilename("../CON", names)).isEqualTo("_CON.url");
        assertThat(policy.manualShortcutFilename("Mi/App", names)).isEqualTo("Mi-App.url");
        assertThat(policy.manualShortcutFilename("mi-app", names)).isEqualTo("mi-app-2.url");
    }

    /**
     * Construye una fuente de prueba con nombre propuesto e identidades derivadas de la semilla.
     *
     * @param id Texto de fixture del que se derivan UUID estables de elemento, aplicación y fuente.
     * @param filename Nombre propuesto que se conserva para probar saneamiento o configuración del
     *     instalador.
     * @return resolución Windows cuyos nombres puede sanear la política.
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
