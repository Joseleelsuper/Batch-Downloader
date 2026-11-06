package es.ubu.batchdownloader.softwarerequest;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.CreateSoftwareRequest;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.ReviewSoftwareRequest;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.SoftwareRequest;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.SoftwareRequestPage;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Las rutas personales solo consultan el UUID autenticado; revisión reservada a ADMIN. */
@RestController
public class SoftwareRequestController {
    private final SoftwareRequestRepository requests;
    private final CurrentAccount accounts;
    private final AdminAuditService audit;

    public SoftwareRequestController(SoftwareRequestRepository requests, CurrentAccount accounts, AdminAuditService audit) {
        this.requests = requests;
        this.accounts = accounts;
        this.audit = audit;
    }

    @GetMapping("/api/v1/users/me/software-requests")
    SoftwareRequestPage own(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, Authentication authentication) {
        return requests.list(accounts.require(authentication).id(), null, page, pageSize);
    }

    @PostMapping("/api/v1/users/me/software-requests")
    @ResponseStatus(HttpStatus.CREATED)
    SoftwareRequest create(@Valid @RequestBody CreateSoftwareRequest request, Authentication authentication) {
        return requests.create(accounts.require(authentication).id(), request.name(), request.officialUrl());
    }

    @DeleteMapping("/api/v1/users/me/software-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID requestId, Authentication authentication) {
        requests.cancel(accounts.require(authentication).id(), requestId);
    }

    @GetMapping("/api/v1/admin/software-requests")
    SoftwareRequestPage admin(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        accounts.require(authentication);
        return requests.list(null, status, page, pageSize);
    }

    @PatchMapping("/api/v1/admin/software-requests/{requestId}")
    SoftwareRequest review(@PathVariable UUID requestId, @Valid @RequestBody ReviewSoftwareRequest request,
            Authentication authentication) {
        UUID reviewer = accounts.require(authentication).id();
        try {
            var result = requests.review(requestId, reviewer, request);
            audit.record(reviewer.toString(), "software-request.review", "software-request", requestId.toString(),
                    Map.of("status", result.status(), "result", "success"));
            return result;
        } catch (RuntimeException exception) {
            audit.record(reviewer.toString(), "software-request.review", "software-request", requestId.toString(),
                    Map.of("result", "failure", "failureType", exception.getClass().getSimpleName()));
            throw exception;
        }
    }
}
