package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilenamePolicyTest {
    private final FilenamePolicy policy = new FilenamePolicy();

    @Test
    void sanitizesTraversalAndDeduplicatesCaseInsensitively() {
        Set<String> names = policy.newNameSet();
        ResolvedDownloadItem first = item("one", "../Setup.EXE");
        ResolvedDownloadItem second = item("two", "setup.exe");

        assertThat(policy.filenameFor(first, names)).isEqualTo("Setup.EXE");
        assertThat(policy.filenameFor(second, names)).isEqualTo("setup-2.exe");
    }

    @Test
    void preservesCompoundTarGzExtensionWhenAddingSuffix() {
        Set<String> names = policy.newNameSet();
        assertThat(policy.filenameFor(item("one", "tool.tar.gz"), names)).isEqualTo("tool.tar.gz");
        assertThat(policy.filenameFor(item("two", "tool.tar.gz"), names)).isEqualTo("tool-2.tar.gz");
    }

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
