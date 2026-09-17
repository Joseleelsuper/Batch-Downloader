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

/**
 * Expone gestión personal de bundles bajo el UUID de una cuenta USER habilitada y exige versión en
 * las ediciones.
 *
 * @see es.ubu.batchdownloader.bundle.UserBundleRepository
 * @see es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@RestController
@RequestMapping("/api/v1/users/me/bundles")
public class UserBundleController {
    private final UserBundleRepository bundles;
    private final CurrentAccount currentAccount;

    /**
     * Conecta operaciones personales con la comprobación de la cuenta vigente.
     *
     * @param bundles Repositorio que exige propiedad UUID en cada operación personal.
     * @param currentAccount Resolución de una cuenta habilitada a partir de la identidad UUID de
     *     sesión.
     */
    public UserBundleController(UserBundleRepository bundles, CurrentAccount currentAccount) {
        this.bundles = bundles;
        this.currentAccount = currentAccount;
    }

    /**
     * Pagina bundles del propietario con tamaño de 1–60 y página mínima uno.
     *
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return página personal con total antes de paginar.
     */
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

    /**
     * Crea un bundle privado del UUID autenticado con sus etiquetas y selección ordenada.
     *
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return 201 con el detalle personal y su versión inicial.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OwnBundleDetails create(
            @Valid @RequestBody CreateOwnBundleRequest request,
            Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        return bundles.create(account.id(), request);
    }

    /**
     * Consulta únicamente un bundle personal perteneciente al UUID de la sesión.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return detalle con versión para editar.
     */
    @GetMapping("/{bundleId}")
    OwnBundleDetails details(@PathVariable String bundleId, Authentication authentication) {
        return bundles.details(currentAccount.require(authentication).id(), bundleId);
    }

    /**
     * Guarda una edición del propietario solo si expectedVersion sigue vigente.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return detalle con la nueva versión; una edición obsoleta produce conflicto.
     */
    @PatchMapping("/{bundleId}")
    OwnBundleDetails update(
            @PathVariable String bundleId,
            @Valid @RequestBody UpdateOwnBundleRequest request,
            Authentication authentication) {
        return bundles.update(currentAccount.require(authentication).id(), bundleId, request);
    }

    /**
     * Elimina el bundle personal solo si pertenece a la cuenta; devuelve 204 al completarse.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     */
    @DeleteMapping("/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String bundleId, Authentication authentication) {
        bundles.delete(currentAccount.require(authentication).id(), bundleId);
    }
}
