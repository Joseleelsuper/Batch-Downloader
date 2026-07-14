package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.bundle.BundleRepository;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
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

@RestController
@RequestMapping("/api/v1/download-jobs")
public class DownloadJobController {
    static final String OWNER_COOKIE = "BATCH_DOWNLOAD_OWNER";
    private static final Set<String> OPERATING_SYSTEMS = Set.of("windows", "linux", "macos");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DownloadJobService jobs;
    private final DownloadRequestOwner owners;
    private final BundleRepository bundles;
    private final SseDownloadJobNotifier notifier;
    private final boolean secureCookie;

    public DownloadJobController(
            DownloadJobService jobs,
            DownloadRequestOwner owners,
            BundleRepository bundles,
            SseDownloadJobNotifier notifier,
            @Value("${app.download.anonymous-cookie-secure}") boolean secureCookie) {
        this.jobs = jobs;
        this.owners = owners;
        this.bundles = bundles;
        this.notifier = notifier;
        this.secureCookie = secureCookie;
    }

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
                : bundles.appIdsForDownload(request.bundleId(), actor(authentication), isAdmin(authentication));
        DownloadJobView created = jobs.create(
                owner,
                appIds,
                normalizedOperatingSystems(request.operatingSystems()),
                request.notifyWhenReady());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (anonymous && (browserToken == null || browserToken.isBlank())) {
            response.header(HttpHeaders.SET_COOKIE, ownerCookie(token).toString());
        }
        return response.body(created);
    }

    @GetMapping("/{jobId}")
    DownloadJobView get(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return jobs.get(requestOwner(authentication, browserToken, servletRequest), jobId);
    }

    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return notifier.subscribe(jobs.get(requestOwner(authentication, browserToken, servletRequest), jobId));
    }

    @DeleteMapping("/{jobId}")
    ResponseEntity<DownloadJobView> cancel(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        return ResponseEntity.accepted()
                .body(jobs.cancel(requestOwner(authentication, browserToken, servletRequest), jobId));
    }

    @GetMapping("/{jobId}/file")
    ResponseEntity<Void> file(
            @PathVariable UUID jobId,
            Authentication authentication,
            @CookieValue(value = OWNER_COOKIE, required = false) String browserToken,
            HttpServletRequest servletRequest) {
        URI location = jobs.file(requestOwner(authentication, browserToken, servletRequest), jobId);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }

    private RequestOwner requestOwner(
            Authentication authentication, String browserToken, HttpServletRequest servletRequest) {
        return owners.resolve(
                actor(authentication),
                browserToken,
                servletRequest.getRemoteAddr());
    }

    private List<UUID> distinctAppIds(List<UUID> appIds) {
        return appIds == null
                ? List.of()
                : appIds.stream().filter(java.util.Objects::nonNull).collect(
                        java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                                List::copyOf));
    }

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
    }

    private String ensureBrowserToken(String token) {
        if (token != null && !token.isBlank()) {
            return token;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie ownerCookie(String token) {
        return ResponseCookie.from(OWNER_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(java.time.Duration.ofHours(24))
                .build();
    }

    private String actor(Authentication authentication) {
        return isSignedIn(authentication) ? authentication.getName() : null;
    }

    private boolean isAdmin(Authentication authentication) {
        return isSignedIn(authentication) && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    record CreateDownloadJobRequest(
            @Size(max = 100) List<UUID> appIds,
            String bundleId,
            List<String> operatingSystems,
            boolean notifyWhenReady) {}
}
