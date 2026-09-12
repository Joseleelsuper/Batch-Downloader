package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.bundle.BundleRepository;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService;
import es.ubu.batchdownloader.downloads.application.DownloadSelection;
import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Expone creación, consulta, cancelación, eventos y entrega de ZIP con identidad de cuenta o cookie
 * anónima y validación de la selección.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @see es.ubu.batchdownloader.downloads.infrastructure.web.SseDownloadJobNotifier
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@RestController
@RequestMapping("/api/v1/download-jobs")
public class DownloadJobController {
    /**
     * Valor compartido que fija o w n e r  c o o k i e para el comportamiento del componente.
     */
    static final String OWNER_COOKIE = "BATCH_DOWNLOAD_OWNER";
    /**
     * Valor compartido que fija o p e r a t i n g  s y s t e m s para el comportamiento del
     * componente.
     */
    private static final Set<String> OPERATING_SYSTEMS = Set.of("windows", "linux", "macos");
    /**
     * Valor compartido que fija r a n d o m para el comportamiento del componente.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Estado {@code jobs} mantenido por {@code DownloadJobController}.
     */
    private final DownloadJobService jobs;
    private final DownloadJobAccessService access;
    /**
     * Estado {@code owners} mantenido por {@code DownloadJobController}.
     */
    private final DownloadRequestOwner owners;
    /**
     * Estado {@code bundles} mantenido por {@code DownloadJobController}.
     */
    private final BundleRepository bundles;
    /**
     * Estado {@code notifier} mantenido por {@code DownloadJobController}.
     */
    private final SseDownloadJobNotifier notifier;
    /** Admisión rápida del almacenamiento temporal del worker. */
    private final DownloadWorkerCapacityClient workerCapacity;
    /**
     * Estado {@code secureCookie} mantenido por {@code DownloadJobController}.
     */
    private final boolean secureCookie;

    /**
     * Conecta admisión, acceso, bundles y capacidad del worker con la configuración de la cookie
     * anónima.
     *
     * @param jobs Caso de uso de admisión y previsualización de nuevas selecciones.
     * @param access Caso de uso de consulta, cancelación y entrega con comprobación del
     *     propietario.
     * @param owners Derivación de la identidad de cuenta o navegador usada para autorizar el
     *     trabajo.
     * @param bundles Consulta de aplicaciones de bundles bajo sus permisos de acceso.
     * @param notifier Difusor SSE de instantáneas ya autorizadas.
     * @param workerCapacity Cliente que comprueba capacidad temporal antes de admitir otro ZIP.
     * @param secureCookie Activa el atributo Secure de la cookie anónima cuando el despliegue
     *     utiliza HTTPS.
     */
    public DownloadJobController(
            DownloadJobService jobs,
            DownloadJobAccessService access,
            DownloadRequestOwner owners,
            BundleRepository bundles,
            SseDownloadJobNotifier notifier,
            DownloadWorkerCapacityClient workerCapacity,
            @Value("${app.download.anonymous-cookie-secure}") boolean secureCookie) {
        this.jobs = jobs;
        this.access = access;
        this.owners = owners;
        this.bundles = bundles;
        this.notifier = notifier;
        this.workerCapacity = workerCapacity;
        this.secureCookie = secureCookie;
    }

    /**
     * Valida selección o bundle y capacidad del worker antes de admitir el trabajo; crea una cookie
     * opaca solo cuando falta identidad anónima.
     *
     * @param request Selección validada de aplicaciones o bundle, fuente exacta y destino Linux
     *     opcionales.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return 202 con el trabajo admitido y, si corresponde, Set-Cookie para acceder después.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la selección, plataforma o uso
     *     de fuente exacta son inválidos.
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si el worker o la cola no
     *     tienen capacidad temporal.
     */
    @PostMapping
    ResponseEntity<DownloadJobView> create(
            @Valid @RequestBody CreateDownloadJobRequest request,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        validateSource(request);
        boolean anonymous = !isSignedIn(authentication);
        String token = anonymous ? ensureBrowserToken(browserToken) : browserToken;
        RequestOwner owner = requestOwner(authentication, token, servletRequest);
        List<UUID> appIds = request.bundleId() == null
                ? distinctAppIds(request.appIds())
                : bundleAppIds(request.bundleId(), authentication);
        workerCapacity.requireAvailable();
        DownloadJobView created = jobs.create(owner,
                new DownloadSelection(appIds, normalizedOperatingSystems(request.operatingSystems()),
                        request.sourceRef(), request.linuxTarget(), request.targetArchitecture()),
                request.notifyWhenReady());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (anonymous && (browserToken == null || browserToken.isBlank())) {
            response.header(HttpHeaders.SET_COOKIE, ownerCookie(token).toString());
        }
        return response.body(created);
    }

    /**
     * Resuelve la selección o bundle accesible y evalúa el destino Linux sin crear un trabajo.
     *
     * @param request Selección validada de aplicaciones o bundle, fuente exacta y destino Linux
     *     opcionales.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @return compatibilidad, alternativas manuales y dependencias de la selección.
     */
    @PostMapping("/linux-preview")
    DownloadJobService.LinuxPreview preview(@Valid @RequestBody CreateDownloadJobRequest request,
            Authentication authentication) {
        validateSource(request);
        List<UUID> appIds = request.bundleId() == null ? distinctAppIds(request.appIds())
                : bundleAppIds(request.bundleId(), authentication);
        return jobs.previewLinux(new DownloadSelection(appIds, normalizedOperatingSystems(request.operatingSystems()), request.sourceRef(), request.linuxTarget(), request.targetArchitecture()));
    }

    /**
     * Consulta el trabajo con la identidad autenticada o el hash del navegador solicitante.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return vista del trabajo accesible.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el trabajo no existe o no
     *     pertenece al solicitante.
     */
    @GetMapping("/{jobId}")
    DownloadJobView get(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return access.get(requestOwner(authentication, browserToken, servletRequest), jobId);
    }

    /**
     * Comprueba acceso antes de suscribir al navegador a cambios SSE e inmediatamente enviar la
     * vista inicial.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return conexión SSE que termina cuando el trabajo alcanza un estado terminal.
     */
    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return notifier.subscribe(access.get(requestOwner(authentication, browserToken, servletRequest), jobId));
    }

    /**
     * Solicita cancelación cooperativa con la misma comprobación de propietario utilizada en las
     * consultas.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return 202 con la vista resultante de la solicitud de cancelación.
     */
    @DeleteMapping("/{jobId}")
    ResponseEntity<DownloadJobView> cancel(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return ResponseEntity.accepted()
                .body(access.cancel(requestOwner(authentication, browserToken, servletRequest), jobId));
    }

    /**
     * Obtiene permiso temporal de lectura del ZIP y redirige el navegador al almacén.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return 303 con Location firmado; la disponibilidad y el propietario se validan antes de
     *     firmar.
     */
    @GetMapping("/{jobId}/file")
    ResponseEntity<Void> file(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        URI location = access.file(requestOwner(authentication, browserToken, servletRequest), jobId);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }

    /**
     * Entrega en JSON un enlace temporal del ZIP para clientes que no pueden seguir directamente la
     * redirección.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return 200 con la URL y Cache-Control no-store.
     */
    @GetMapping("/{jobId}/file-link")
    ResponseEntity<DownloadFileLink> fileLink(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        URI location = access.file(requestOwner(authentication, browserToken, servletRequest), jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new DownloadFileLink(location.toASCIIString()));
    }

    /**
     * Transporta al navegador un permiso temporal de lectura del ZIP que no debe almacenarse en
     * caché.
     *
     * @param url Enlace temporal de lectura que el navegador puede abrir sin otra petición a Core.
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record DownloadFileLink(String url) {}

    /**
     * Resuelve el UUID de una cuenta reconocida; para el resto deriva los hashes de cookie y
     * dirección remota.
     *
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @param browserToken Token opaco de la cookie del navegador; vacío o null significa que
     *     todavía no existe.
     * @param servletRequest Solicitud HTTP de la que se obtiene la dirección remota para las
     *     cuotas.
     * @return identidad utilizada para propiedad y cuotas del trabajo.
     */
    private RequestOwner requestOwner(
            Authentication authentication, String browserToken, HttpServletRequest servletRequest) {
        if (isSignedIn(authentication)
                && authentication.getPrincipal() instanceof AccountPrincipal account) {
            return owners.resolve(account.userId(), browserToken, servletRequest.getRemoteAddr());
        }
        return owners.resolve(null, browserToken, servletRequest.getRemoteAddr());
    }

    /**
     * Obtiene las aplicaciones del bundle bajo los permisos del propietario, administrador o
     * visitante.
     *
     * @param bundleId Identificador del bundle cuya selección exige comprobar visibilidad y
     *     propietario.
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @return selección accesible del bundle antes de aplicar la admisión.
     */
    private List<UUID> bundleAppIds(String bundleId, Authentication authentication) {
        if (isSignedIn(authentication)
                && authentication.getPrincipal() instanceof AccountPrincipal account) {
            return bundles.appIdsForDownload(bundleId, account.userId(), isAdmin(authentication));
        }
        return bundles.appIdsForDownload(bundleId, null, isAdmin(authentication));
    }

    /**
     * Retira UUID nulos y repetidos conservando la primera aparición de cada aplicación.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @return lista inmutable; vacía si la entrada es null.
     */
    private List<UUID> distinctAppIds(List<UUID> appIds) {
        return appIds == null
                ? List.of()
                : appIds.stream().filter(java.util.Objects::nonNull).collect(
                        java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                                List::copyOf));
    }

    /**
     * Recorta y normaliza plataformas sin duplicados; representar todas mediante una lista vacía
     * mantiene el contrato de selección automática.
     *
     * @param values Plataformas recibidas; null o una lista vacía permiten todas las plataformas
     *     soportadas.
     * @return plataformas conocidas en minúsculas o lista vacía para todas.
     * @throws es.ubu.batchdownloader.common.BadRequestException si hay plataformas desconocidas o
     *     una lista explícita solo contiene valores vacíos.
     */
    private List<String> normalizedOperatingSystems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.isEmpty() || !OPERATING_SYSTEMS.containsAll(normalized)) {
            throw new BadRequestException("invalid_operating_system", "El sistema operativo indicado no es válido.");
        }
        return normalized.size() == OPERATING_SYSTEMS.size() ? List.of() : normalized;
    }

    /**
     * Exige exactamente aplicaciones o bundle y permite fuente exacta solo para una única
     * aplicación explícita.
     *
     * @param request Selección validada de aplicaciones o bundle, fuente exacta y destino Linux
     *     opcionales.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la selección es ambigua, vacía o
     *     incompatible con una fuente exacta.
     */
    private void validateSource(CreateDownloadJobRequest request) {
        boolean hasApps = request.appIds() != null;
        boolean hasBundle = request.bundleId() != null && !request.bundleId().isBlank();
        if (hasApps == hasBundle) {
            throw new BadRequestException(
                    "invalid_download_request", "Indica exactamente una selección de aplicaciones o un bundle.");
        }
        if (hasApps && request.appIds().isEmpty()) {
            throw new BadRequestException("invalid_job_size", "Selecciona al menos una aplicación.");
        }
        if (request.sourceRef() != null
                && (!hasApps || request.appIds().size() != 1)) {
            throw new BadRequestException(
                    "invalid_source_selection",
                    "La fuente seleccionada requiere una única aplicación.");
        }
    }

    /**
     * Conserva el token existente o genera 32 bytes aleatorios codificados como Base64 URL sin
     * relleno.
     *
     * @param token Token opaco de la cookie; null o blanco provoca la creación de uno nuevo.
     * @return identificador opaco del navegador que Core almacenará únicamente como HMAC.
     */
    private String ensureBrowserToken(String token) {
        if (token != null && !token.isBlank()) {
            return token;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Construye la cookie de propiedad anónima con HttpOnly, SameSite Lax, ruta raíz y vigencia de
     * 24 horas.
     *
     * @param token Token opaco generado para este navegador.
     * @return cookie con Secure conforme a la configuración del despliegue.
     */
    private ResponseCookie ownerCookie(String token) {
        return ResponseCookie.from(OWNER_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(java.time.Duration.ofHours(24))
                .build();
    }

    /**
     * Obtiene el nombre de la sesión únicamente cuando está autenticada y no es anónima.
     *
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @return nombre de sesión o null.
     */
    private String actor(Authentication authentication) {
        return isSignedIn(authentication) ? authentication.getName() : null;
    }

    /**
     * Comprueba el rol ROLE_ADMIN dentro de una sesión autenticada no anónima.
     *
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @return true si la sesión tiene autoridad administrativa.
     */
    private boolean isAdmin(Authentication authentication) {
        return isSignedIn(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    /**
     * Distingue una sesión autenticada de una autenticación ausente o anónima de Spring.
     *
     * @param authentication Sesión de Spring Security, o null cuando no hay una identidad
     *     autenticada.
     * @return true únicamente para sesiones autenticadas no anónimas.
     */
    private boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Describe una selección explícita o un bundle, plataformas y preferencias opcionales; la
     * validación cruzada impide mezclarlos y restringe la fuente exacta.
     *
     * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
     * @param bundleId Identificador del bundle cuya selección exige comprobar visibilidad y
     *     propietario.
     * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su
     *     política de selección.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     * @param linuxTarget Gestor Linux opcional; junto con arquitectura define un destino explícito.
     * @param targetArchitecture Arquitectura Linux opcional que acompaña al gestor explícito.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    record CreateDownloadJobRequest(
            @Size(max = 100) List<UUID> appIds,
            String bundleId,
            List<String> operatingSystems,
            UUID sourceRef,
            boolean notifyWhenReady,
            String linuxTarget,
            String targetArchitecture) {}
}
