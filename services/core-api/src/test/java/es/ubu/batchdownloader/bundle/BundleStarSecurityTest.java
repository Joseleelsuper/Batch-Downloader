package es.ubu.batchdownloader.bundle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifica autenticación y CSRF reales en estrellas y mantenimiento de bundles oficiales. */
@WebMvcTest(BundleController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"server.port=0","server.servlet.session.cookie.secure=false",
        "spring.session.timeout=30m","app.security.require-https=false","app.auth.bcrypt-strength=4",
        "app.auth.hash-concurrency=2","app.auth.hash-queue=20","app.auth.hash-wait=2s"})
class BundleStarSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean BundleRepository bundles;
    @MockitoBean AdminAuditService audit;
    @MockitoBean BundleCatalogService catalog;
    @MockitoBean BundleActivityRepository activity;
    @MockitoBean CurrentAccount accounts;
    @MockitoBean UserAccountStore users;

    @Test void anonymousCannotVoteEvenWithValidCsrf() throws Exception {
        mvc.perform(put("/api/v1/bundles/example/star").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/bundles/example/star").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test void bothAccountRolesNeedCsrfAndCanAddAndRemoveTheirOwnStar() throws Exception {
        UUID id = UUID.randomUUID();
        UserAccount account = mock(UserAccount.class);
        when(account.id()).thenReturn(id);
        when(accounts.require(any())).thenReturn(account);
        when(activity.setStar(anyString(),any(),anyBoolean())).thenReturn(new BundleDtos.BundleStarState(1,true,false,0));
        for (UserRole role : UserRole.values()) {
            var principal = new AccountPrincipal(id,"account",role);
            var token = new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
            mvc.perform(put("/api/v1/bundles/example/star").with(authentication(token))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/bundles/example/star").with(authentication(token))).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/bundles/example/star").with(authentication(token)).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.starCount").value(1));
            mvc.perform(delete("/api/v1/bundles/example/star").with(authentication(token)).with(csrf())).andExpect(status().isOk());
        }
    }

    @Test void officialBundleCreationEditionAndWithdrawalRequireAnAdminAndCsrfAndAreAudited() throws Exception {
        UUID actor = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        String payload = "{\"name\":\"Oficial\",\"type\":\"official\",\"visibility\":\"official\",\"appIds\":[]}";
        for (var mutation : java.util.List.of(post("/api/v1/admin/bundles"),
                patch("/api/v1/admin/bundles/{bundleId}", id), delete("/api/v1/admin/bundles/{bundleId}", id))) {
            mvc.perform(mutation.with(anonymous()).with(csrf()).contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
            var user = new AccountPrincipal(actor, "user", UserRole.USER);
            mvc.perform(mutation.with(authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()))))
                    .andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(bundles, audit);
        var admin = new AccountPrincipal(actor, "admin", UserRole.ADMIN);
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        mvc.perform(post("/api/v1/admin/bundles").with(auth).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        var details = new BundleDtos.BundleDetails(id, "official", "Oficial", null, "official", "official", 0, 0,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.time.LocalDateTime.now());
        when(bundles.create(any(), any())).thenReturn(details);
        when(bundles.update(anyString(), any())).thenReturn(details);
        mvc.perform(post("/api/v1/admin/bundles").with(auth).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("official"));
        mvc.perform(patch("/api/v1/admin/bundles/{bundleId}", id).with(auth).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/bundles/{bundleId}", id).with(auth).with(csrf())).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(bundles).delete(id);
        for (String action : java.util.List.of("bundle.create", "bundle.update", "bundle.delete")) {
            org.mockito.Mockito.verify(audit).record(actor.toString(), action, "bundle", id, null);
        }
    }
}
