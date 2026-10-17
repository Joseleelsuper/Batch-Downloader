package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.bundle.BundleDtos.CreateCustomTagRequest;
import es.ubu.batchdownloader.bundle.BundleDtos.CustomTag;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Expone al administrador la misma lista global de etiquetas de bundles que utilizan los usuarios. */
@RestController
@RequestMapping("/api/v1/admin/bundles/custom-tags")
public class AdminBundleCustomTagController {
    private final UserBundleRepository bundles;

    public AdminBundleCustomTagController(UserBundleRepository bundles) {
        this.bundles = bundles;
    }

    @GetMapping
    List<String> customTags() {
        return bundles.customTags();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomTag createCustomTag(@Valid @RequestBody CreateCustomTagRequest request,
            Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AccountPrincipal account)) {
            throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
        }
        return new CustomTag(bundles.createCustomTag(account.userId(), request.tag()));
    }
}
