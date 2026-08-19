package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** Verifica los invariantes cruzados de los contratos administrativos. */
class AdminDtosTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** Una app sin web oficial no debe inventar una comprobación que no se realizó. */
    @Test
    void absenceEvidenceWithoutOfficialPageRequiresFalseConfirmation() {
        InstallerAbsenceVerificationRequest valid = request(null, false);
        InstallerAbsenceVerificationRequest invented = request(null, true);

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(invented))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .equals("officialConfirmationValid"));
    }

    /** Si existe una página oficial, su comprobación afirmativa es obligatoria. */
    @Test
    void absenceEvidenceWithOfficialPageRequiresTrueConfirmation() {
        InstallerAbsenceVerificationRequest valid = request(
                "https://vendor.example/downloads", true);
        InstallerAbsenceVerificationRequest unchecked = request(
                "https://vendor.example/downloads", false);

        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(unchecked))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .equals("officialConfirmationValid"));
    }

    private InstallerAbsenceVerificationRequest request(
            String officialPageUrl, boolean officialConfirmedAbsent) {
        return new InstallerAbsenceVerificationRequest(
                "no_supported_binary",
                "https://github.com/vendor/package/blob/main/manifest.yaml",
                officialPageUrl,
                true,
                true,
                officialConfirmedAbsent,
                false,
                "Comprobación manual reproducible.");
    }
}
