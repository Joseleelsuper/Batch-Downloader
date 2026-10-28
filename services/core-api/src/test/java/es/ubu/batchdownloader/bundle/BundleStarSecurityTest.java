package es.ubu.batchdownloader.bundle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Verifica autenticación y CSRF reales en las dos mutaciones de estrellas. */
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
}
