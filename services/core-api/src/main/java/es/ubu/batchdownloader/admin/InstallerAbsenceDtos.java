package es.ubu.batchdownloader.admin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Agrupa los contratos de evidencias y comprobaciones de ausencia de instaladores.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.InstallerAbsenceRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class InstallerAbsenceDtos {
    /**
     * Impide instanciar el contenedor de contratos de evidencias y comprobaciones de ausencia de
     * instaladores.
     */
    private InstallerAbsenceDtos() {}

    /**
     * Exige comprobaciones afirmativas de Winstall y manifiesto, coherencia con la web oficial y
     * ausencia de acceso ambiguo para acreditar que no hay instalador compatible.
     *
     * @param reasonCode Motivo verificado: no_supported_binary, store_only, command_only,
     *     wrapper_only o vendor_discontinued.
     * @param manifestUrl URL HTTPS del manifiesto comprobado para confirmar ausencia de instalador.
     * @param officialPageUrl URL HTTPS oficial comprobada; puede faltar solo cuando no hay página
     *     oficial que comprobar.
     * @param winstallConfirmedAbsent Confirma que la consulta de Winstall no encontró un instalador
     *     compatible; debe ser true.
     * @param manifestConfirmedAbsent Confirma que se revisó el manifiesto sin encontrar instalador
     *     compatible; debe ser true.
     * @param officialConfirmedAbsent Debe ser true exactamente cuando se proporciona una página
     *     oficial comprobada.
     * @param ambiguousAccess Debe ser false; un acceso ambiguo no acredita ausencia de instalador.
     * @param notes Explicación administrativa de hasta dos mil caracteres en una confirmación de
     *     ausencia.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record InstallerAbsenceVerificationRequest(
            @NotBlank
            @Pattern(
                    regexp = "no_supported_binary|store_only|command_only|wrapper_only|vendor_discontinued")
            String reasonCode,
            @NotBlank @Pattern(regexp = "(?i)^https://.+") String manifestUrl,
            @Pattern(regexp = "(?i)^https://.+") String officialPageUrl,
            @AssertTrue boolean winstallConfirmedAbsent,
            @AssertTrue boolean manifestConfirmedAbsent,
            boolean officialConfirmedAbsent,
            @AssertFalse boolean ambiguousAccess,
            @Size(max = 2000) String notes) {
        /**
         * Exige confirmación de web oficial exactamente cuando se ha proporcionado una página
         * oficial no vacía.
         *
         * @return true si presencia de página y confirmación coinciden.
         */
        @AssertTrue(message = "officialConfirmedAbsent debe coincidir con officialPageUrl")
        public boolean isOfficialConfirmationValid() {
            boolean hasOfficialPage = officialPageUrl != null && !officialPageUrl.isBlank();
            return hasOfficialPage == officialConfirmedAbsent;
        }
    }

    /**
     * Conserva las páginas, huellas y versión que sustentan una ausencia de instalador y permite
     * conocer cuándo y por qué quedó invalidada.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @param status Estado persistido del flujo o registro descrito, distinto del estado público
     *     del catálogo.
     * @param reasonCode Motivo verificado: no_supported_binary, store_only, command_only,
     *     wrapper_only o vendor_discontinued.
     * @param notes Explicación administrativa de hasta dos mil caracteres en una confirmación de
     *     ausencia.
     * @param checkedUrls JSON con las páginas revisadas que sustenta la evidencia de ausencia.
     * @param verifiedBy UUID textual del administrador que confirmó la evidencia.
     * @param verifiedAt Fecha de la confirmación de ausencia.
     * @param appVersion Versión persistida de la aplicación que acompaña a la evidencia o resultado
     *     de publicación.
     * @param winstallLatestVersion Última versión Winstall que se comprobó al generar la evidencia.
     * @param winstallSummaryFingerprint Huella del resumen Winstall utilizada para detectar cambios
     *     posteriores.
     * @param winstallDetailFingerprint Huella del detalle Winstall utilizado durante la
     *     comprobación.
     * @param officialUrlFingerprint SHA-256 de la página oficial recortada; null cuando no se
     *     conoce página.
     * @param invalidatedAt Fecha en que la evidencia dejó de estar vigente, o null mientras siga
     *     activa.
     * @param invalidationReason Código que explica por qué se retiró la evidencia, o null si sigue
     *     vigente.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record InstallerAbsenceVerification(
            String id,
            String appId,
            String status,
            String reasonCode,
            String notes,
            String checkedUrls,
            String verifiedBy,
            LocalDateTime verifiedAt,
            long appVersion,
            String winstallLatestVersion,
            String winstallSummaryFingerprint,
            String winstallDetailFingerprint,
            String officialUrlFingerprint,
            LocalDateTime invalidatedAt,
            String invalidationReason) {}

    /**
     * Resume evidencia vigente y aplicaciones sin instalador que todavía necesitan comprobación
     * administrativa.
     *
     * @param active Cantidad de evidencias de ausencia actualmente activas.
     * @param missing Aplicaciones activas cuyo estado público es missing.
     * @param missingWithoutActiveEvidence Aplicaciones missing que todavía no tienen evidencia
     *     vigente de ausencia.
     * @param review Aplicaciones activas cuyo estado público es review.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record InstallerAbsenceVerificationSummary(
            long active,
            long missing,
            long missingWithoutActiveEvidence,
            long review) {}

}
