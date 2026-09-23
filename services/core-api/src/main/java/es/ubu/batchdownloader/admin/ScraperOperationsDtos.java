package es.ubu.batchdownloader.admin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agrupa los contratos de observación y control de ejecuciones y colas persistentes.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminScraperRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class ScraperOperationsDtos {
    /**
     * Impide instanciar el contenedor de contratos de observación y control de ejecuciones y colas
     * persistentes.
     */
    private ScraperOperationsDtos() {}

    /**
     * Resume alcance, fechas, avance y parada o pausa de una ejecución del pipeline persistente.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param status Estado de ejecución del scraper.
     * @param scope Alcance incremental, unresolved, selected o full; selected exige una selección
     *     explícita.
     * @param requestId UUID de la solicitud persistida que el scheduler debe reservar.
     * @param targetCount Cantidad de aplicaciones objetivo prevista para la ejecución del scraper.
     * @param startedAt Fecha de inicio de la última ejecución del scraper.
     * @param heartbeatAt Fecha de su último latido persistido.
     * @param finishedAt Fecha de finalización o null si la ejecución todavía no ha terminado.
     * @param appsDiscovered Aplicaciones descubiertas durante la ejecución.
     * @param appsResolved Aplicaciones resueltas durante la ejecución.
     * @param appsFailed Aplicaciones cuyo procesamiento terminó con error.
     * @param appsSkipped Aplicaciones omitidas por las reglas de la ejecución.
     * @param appsConfirmedMissing Aplicaciones cuyo instalador se confirmó ausente durante la
     *     ejecución.
     * @param appsNeedsReview Aplicaciones derivadas a revisión durante la ejecución.
     * @param appsTransientFailed Aplicaciones afectadas por fallos temporales en esa ejecución.
     * @param appsSkippedUnchanged Aplicaciones omitidas porque sus huellas no cambiaron.
     * @param currentPackageId Paquete que se está procesando, o null si no hay uno activo.
     * @param currentAppName Nombre visible de la aplicación actualmente procesada.
     * @param currentPhase Fase actual del pipeline de scraping.
     * @param stopRequested El scheduler debe detener la ejecución de forma cooperativa.
     * @param pausedAt Fecha de pausa vigente, o null si no está pausada.
     * @param errorSummary Resumen seguro de un fallo de ejecución, sin datos protegidos del
     *     instalador.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperRunSummary(
            String id,
            String status,
            String scope,
            String requestId,
            int targetCount,
            LocalDateTime startedAt,
            LocalDateTime heartbeatAt,
            LocalDateTime finishedAt,
            int appsDiscovered,
            int appsResolved,
            int appsFailed,
            int appsSkipped,
            int appsConfirmedMissing,
            int appsNeedsReview,
            int appsTransientFailed,
            int appsSkippedUnchanged,
            String currentPackageId,
            String currentAppName,
            String currentPhase,
            boolean stopRequested,
            LocalDateTime pausedAt,
            String errorSummary) {}

    /**
     * Solicita una ejecución por alcance; hasta quinientos UUID solo se admiten cuando el alcance
     * es selected.
     *
     * @param scope Alcance incremental, unresolved, selected o full; selected exige una selección
     *     explícita.
     * @param appIds UUID de aplicaciones seleccionadas; hasta quinientas para una ejecución con
     *     alcance selected.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperRunRequest(
            @NotBlank
            @Pattern(regexp = "incremental|unresolved|selected|full")
            String scope,
            @Size(max = 500) List<UUID> appIds) {
        /**
         * Exige UUID no vacíos para selected y rechaza selecciones explícitas en otros alcances.
         *
         * @return true si alcance y presencia de selección son coherentes.
         */
        @AssertTrue(message = "selected requiere appIds y los demás scopes no los admiten")
        public boolean isSelectionValid() {
            boolean hasIds = appIds != null && !appIds.isEmpty();
            return "selected".equals(scope) == hasIds;
        }
    }

    /**
     * Confirma la identidad y el alcance de una solicitud que el scheduler procesará
     * posteriormente.
     *
     * @param requestId UUID de la solicitud persistida que el scheduler debe reservar.
     * @param scope Alcance incremental, unresolved, selected o full; selected exige una selección
     *     explícita.
     * @param status Estado pending de la solicitud recién encolada.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperRunRequestResponse(String requestId, String scope, String status) {}

    /**
     * Presenta un registro de fase y resultado de resolución con mensaje y metadatos seguros.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param phase Etapa actual del flujo persistente de inspección, descubrimiento o resolución.
     * @param status Estado persistido del flujo o registro descrito, distinto del estado público
     *     del catálogo.
     * @param message Explicación segura del registro de resolución para el panel administrativo.
     * @param safeMetadata JSON de diagnóstico o auditoría limitado a campos seguros.
     * @param createdAt Fecha de creación del registro, inspección o solicitud.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ResolverLogItem(
            String id,
            String phase,
            String status,
            String message,
            String safeMetadata,
            LocalDateTime createdAt) {}

    /**
     * Resume identidad, aplicación, estado e intentos de un elemento persistente de cola.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param packageId Identificador Winstall o manual de la aplicación, distinto de su UUID
     *     público.
     * @param appName Nombre visible de la aplicación asociada al elemento.
     * @param status Estado persistido del flujo o registro descrito, distinto del estado público
     *     del catálogo.
     * @param attempts Número de intentos registrados del elemento de cola.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperQueueItem(
            String id,
            String packageId,
            String appName,
            String status,
            int attempts,
            LocalDateTime updatedAt) {}

    /**
     * Agrupa contadores de estado y una muestra de elementos de una etapa del pipeline.
     *
     * @param queue Nombre de la etapa de cola persistente.
     * @param queued Elementos pendientes de reserva en esta cola.
     * @param inProgress Elementos con trabajo en progreso.
     * @param completed Elementos que finalizaron correctamente.
     * @param discarded Elementos descartados por la política de esa etapa.
     * @param failed Elementos que terminaron con fallo.
     * @param items Muestra acotada de elementos recientes de la cola.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperQueueState(
            String queue,
            long queued,
            long inProgress,
            long completed,
            long discarded,
            long failed,
            List<ScraperQueueItem> items) {}

    /**
     * Agrupa versión y colas para actualizar el panel administrativo de forma coherente.
     *
     * @param type Tipo de evento administrativo del scraper.
     * @param version Token opaco calculado a partir del estado observado.
     * @param queues Estados y muestras de las colas persistentes.
     * @param generatedAt Instante UTC en que se construye la estadística o evento.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperEvent(
            String type,
            String version,
            List<ScraperQueueState> queues,
            LocalDateTime generatedAt) {}

    /**
     * Transporta un comando no vacío que el repositorio validará antes de persistirlo para el
     * scheduler.
     *
     * @param command Comando solicitado al scheduler; el repositorio valida las alternativas
     *     admitidas.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperCommandRequest(@NotBlank String command) {}

    /**
     * Identifica la acción de mantenimiento realizada y cuántos elementos modificó.
     *
     * @param action Nombre estable de la acción administrativa registrada.
     * @param affected Número de registros recuperados, reencolados o podados por el mantenimiento.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ScraperQueueMaintenanceResult(String action, int affected) {}

}
