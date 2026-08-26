package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.bundle.UserBundleRepository;
import es.ubu.batchdownloader.identity.api.AccountDtos.AccountDashboard;
import es.ubu.batchdownloader.identity.api.AccountDtos.DownloadHistoryPage;
import es.ubu.batchdownloader.identity.application.AccountProfileService;
import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.persistence.AccountOverviewRepository;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Perfil y área privada de la cuenta USER. */
@RestController
@RequestMapping("/api/v1/users/me")
public class AccountController {
    private final CurrentAccount currentAccount;
    private final IdentityService identities;
    private final AccountProfileService profiles;
    private final AccountOverviewRepository overview;
    private final UserBundleRepository bundles;
    private final SecurityContextRepository securityContexts;

    public AccountController(
            CurrentAccount currentAccount,
            IdentityService identities,
            AccountProfileService profiles,
            AccountOverviewRepository overview,
            UserBundleRepository bundles,
            SecurityContextRepository securityContexts) {
        this.currentAccount = currentAccount;
        this.identities = identities;
        this.profiles = profiles;
        this.overview = overview;
        this.bundles = bundles;
        this.securityContexts = securityContexts;
    }

    @GetMapping
    IdentityView me(Authentication authentication) {
        return identities.findById(currentAccount.require(authentication).id());
    }

    @PatchMapping
    IdentityView update(
            @Valid @RequestBody UsernameRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        UserAccount current = currentAccount.require(authentication);
        IdentityView changed = profiles.changeUsername(current.id(), request.username());
        AccountPrincipal principal = new AccountPrincipal(current.id(), changed.username(), current.role());
        var replacement = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        replacement.setDetails(authentication.getDetails());
        var context = SecurityContextHolder.getContext();
        context.setAuthentication(replacement);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return changed;
    }

    @GetMapping("/downloads")
    DownloadHistoryPage downloads(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(60, pageSize));
        return new DownloadHistoryPage(
                overview.downloads(account.id(), safePage, safePageSize), safePage, safePageSize,
                overview.downloadCount(account.id()));
    }

    @GetMapping("/dashboard")
    AccountDashboard dashboard(Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        return new AccountDashboard(
                identities.findById(account.id()),
                overview.counts(account.id()),
                overview.downloads(account.id(), 1, 10),
                bundles.list(account.id(), 1, 6));
    }

    record UsernameRequest(@NotBlank @Size(min = 3, max = 40) String username) {}
}
