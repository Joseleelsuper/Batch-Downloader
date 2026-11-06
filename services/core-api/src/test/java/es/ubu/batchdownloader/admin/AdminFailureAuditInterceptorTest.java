package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class AdminFailureAuditInterceptorTest {
    private static final String ROUTE = "/api/v1/admin/bundles/{bundleId}";
    private final UUID actor = UUID.randomUUID();
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .addInterceptors(new AdminFailureAuditInterceptor(() -> audit)).build();

    private UsernamePasswordAuthenticationToken principal(UserRole role) {
        var account = new AccountPrincipal(actor, "display-name-not-for-audit", role);
        return new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities());
    }

    @Test void failedMutationKeepsOnlyTheCanonicalTargetAndRouteTemplate() throws Exception {
        UUID bundle = UUID.randomUUID();
        mvc.perform(post(ROUTE, bundle).principal(principal(UserRole.ADMIN))
                .queryParam("access_token", "never-store-this").content("private request content"))
                .andExpect(status().isConflict());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(actor.toString()), eq("admin.request.failed"), eq("admin-route"), eq(bundle.toString()), metadata.capture());
        assertThat(metadata.getValue()).containsOnlyKeys("outcome", "method", "status", "route")
                .containsEntry("outcome", "failure").containsEntry("status", 409).containsEntry("route", ROUTE);
        assertThat(metadata.getValue().toString()).doesNotContain("never-store-this", "private request", bundle.toString(), "display-name");
    }

    @Test void arbitraryTargetTextIsNotPersisted() throws Exception {
        mvc.perform(post(ROUTE, "personal@example.test").principal(principal(UserRole.ADMIN))).andExpect(status().isConflict());
        verify(audit).record(eq(actor.toString()), eq("admin.request.failed"), eq("admin-route"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.argThat(metadata ->
                        !metadata.toString().contains("personal@example.test")));
    }

    @Test void unhandledFailureIsRecordedWithoutCopyingItsMessage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(post(ROUTE, UUID.randomUUID())
                .param("status", "599").principal(principal(UserRole.ADMIN))))
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(audit).record(eq(actor.toString()), eq("admin.request.failed"), eq("admin-route"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.argThat(metadata ->
                        metadata.get("status").equals(500) && !metadata.toString().contains("private exception")));
    }

    @Test void successfulWritesReadsAndNonAdministratorsDoNotCreateGenericFailureEvents() throws Exception {
        mvc.perform(post(ROUTE, UUID.randomUUID()).param("status", "200").principal(principal(UserRole.ADMIN))).andExpect(status().isOk());
        mvc.perform(get(ROUTE, UUID.randomUUID()).principal(principal(UserRole.ADMIN))).andExpect(status().isConflict());
        mvc.perform(post(ROUTE, UUID.randomUUID()).principal(principal(UserRole.USER))).andExpect(status().isConflict());
        mvc.perform(post(ROUTE, UUID.randomUUID())).andExpect(status().isConflict());
        verifyNoInteractions(audit);
    }

    @RestController static final class TestController {
        @PostMapping(ROUTE) ResponseEntity<Void> write(@RequestParam(defaultValue = "409") int status) {
            if (status == 599) throw new IllegalStateException("private exception with a secret");
            return ResponseEntity.status(status).build();
        }
        @GetMapping(ROUTE) ResponseEntity<Void> read() { return ResponseEntity.status(409).build(); }
    }
}
