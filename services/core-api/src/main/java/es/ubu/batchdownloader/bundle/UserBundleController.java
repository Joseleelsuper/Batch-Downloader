package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.CreateOwnBundleRequest;
import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundlePage;
import es.ubu.batchdownloader.bundle.BundleDtos.UpdateOwnBundleRequest;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD de bundles pertenecientes a la cuenta USER autenticada. */
@RestController
@RequestMapping("/api/v1/users/me/bundles")
public class UserBundleController {
    private final UserBundleRepository bundles;
    private final CurrentAccount currentAccount;

    public UserBundleController(UserBundleRepository bundles, CurrentAccount currentAccount) {
        this.bundles = bundles;
        this.currentAccount = currentAccount;
    }

    @GetMapping
    OwnBundlePage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(60, pageSize));
        return new OwnBundlePage(
                bundles.list(account.id(), safePage, safePageSize), safePage, safePageSize,
                bundles.count(account.id()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OwnBundleDetails create(
            @Valid @RequestBody CreateOwnBundleRequest request,
            Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        return bundles.create(account.id(), request);
    }

    @GetMapping("/{bundleId}")
    OwnBundleDetails details(@PathVariable String bundleId, Authentication authentication) {
        return bundles.details(currentAccount.require(authentication).id(), bundleId);
    }

    @PatchMapping("/{bundleId}")
    OwnBundleDetails update(
            @PathVariable String bundleId,
            @Valid @RequestBody UpdateOwnBundleRequest request,
            Authentication authentication) {
        return bundles.update(currentAccount.require(authentication).id(), bundleId, request);
    }

    @DeleteMapping("/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String bundleId, Authentication authentication) {
        bundles.delete(currentAccount.require(authentication).id(), bundleId);
    }
}
