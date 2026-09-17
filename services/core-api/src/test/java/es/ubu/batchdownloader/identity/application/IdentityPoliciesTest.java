package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdentityPoliciesTest {
    @Test
    void derivesAnAsciiUsernameFromTheEmailLocalPart() {
        assertThat(UsernamePolicy.fromEmail("  Jösé.._Demo--@example.com  "))
                .isEqualTo("jose-demo");
        assertThat(UsernamePolicy.fromEmail("++@example.com")).isEqualTo("user");
        assertThat(UsernamePolicy.fromEmail("ab@example.com")).isEqualTo("user-ab");
    }

    @Test
    void createsBoundedCollisionCandidates() {
        String candidate = UsernamePolicy.collisionCandidate(
                "a-very-long-username-that-will-be-truncated-before-the-suffix", "12ab90zx");

        assertThat(candidate).hasSizeLessThanOrEqualTo(40).endsWith("-12ab90zx");
        assertThat(candidate).matches("[a-z0-9][a-z0-9._-]*[a-z0-9]");
    }

    @Test
    void rejectsManualUsernamesWithInvalidEdgesOrCharacters() {
        assertThatThrownBy(() -> UsernamePolicy.validateManual("-invalid"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("invalid_username"));
        assertThatThrownBy(() -> UsernamePolicy.validateManual("two words"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void enforcesBcryptUtf8ByteLimit() {
        String exactly72Bytes = "Á".repeat(34) + "aa1!";
        assertThat(exactly72Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        PasswordPolicy.requireValid(exactly72Bytes);

        assertThatThrownBy(() -> PasswordPolicy.requireValid("Á".repeat(35) + "aa1!"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_too_long"));
        PasswordPolicy.requireValid("Twelve-char1!");

        assertThatThrownBy(() -> PasswordPolicy.requireValid("Aa1!abc"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_too_short"));
    }

    @Test
    void enforcesCharacterClassesButAllowsLegacyPasswordsDuringLogin() {
        assertThatThrownBy(() -> PasswordPolicy.requireValid("abcdefghij1!"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_missing_uppercase"));
        assertThatThrownBy(() -> PasswordPolicy.requireValid("ABCDEFGHIJ1!"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_missing_lowercase"));
        assertThatThrownBy(() -> PasswordPolicy.requireValid("Abcdefghij!?"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_missing_number"));
        assertThatThrownBy(() -> PasswordPolicy.requireValid("Abcdefghij12"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_missing_special"));

        PasswordPolicy.requireSupportedForLogin("legacy-short");
    }
}
