package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifica que la preferencia Linux sea explícita y no altere descargas heredadas. */
class LinuxTargetTest {
    @Test
    void mapsEachPackageManagerToOnlyItsCompatibleFallbacks() {
        assertThat(new LinuxTarget("apt", "x86_64").extensions())
                .containsExactly(".deb", ".appimage", ".tar.gz", ".jar");
        assertThat(new LinuxTarget("dnf", "aarch64").extensions())
                .containsExactly(".rpm", ".appimage", ".tar.gz", ".jar");
        assertThat(new LinuxTarget("pacman", "x86").extensions())
                .containsExactly(".pkg.tar.zst", ".appimage", ".tar.gz", ".jar");
        assertThat(new LinuxTarget("portable", "x86_64").extensions())
                .containsExactly(".appimage", ".tar.gz", ".jar");
    }

    @Test
    void leavesLegacyRequestsUntouchedWhenNoTargetWasProvided() {
        assertThat(LinuxTarget.optional(null, null, List.of("linux"))).isNull();
        assertThat(LinuxTarget.optional(null, null, List.of("windows", "linux"))).isNull();
    }

    @Test
    void rejectsPartialInvalidOrNonLinuxTargets() {
        assertThatThrownBy(() -> LinuxTarget.optional("apt", null, List.of("linux")))
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("invalid_linux_target");
        assertThatThrownBy(() -> LinuxTarget.optional("apk", "x86_64", List.of("linux")))
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("invalid_linux_target");
        assertThatThrownBy(() -> LinuxTarget.optional("apt", "x86_64", List.of("linux", "windows")))
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("linux_target_requires_linux");
    }
}
