package es.ubu.batchdownloader.softwarerequest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig;
import es.ubu.batchdownloader.softwarerequest.SoftwareRequestDtos.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SoftwareRequestController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"server.port=0","server.servlet.session.cookie.secure=false",
        "spring.session.timeout=30m","app.security.require-https=false","app.auth.bcrypt-strength=4",
        "app.auth.hash-concurrency=2","app.auth.hash-queue=20","app.auth.hash-wait=2s"})
class SoftwareRequestSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SoftwareRequestRepository requests;
    @MockitoBean CurrentAccount accounts;
    @MockitoBean AdminAuditService audit;
    final UUID owner = UUID.randomUUID(), requestId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken token(UserRole role) {
        var principal = new AccountPrincipal(owner, "account", role);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private SoftwareRequest request(String status) {
        return new SoftwareRequest(requestId, "Editor", "https://example.org/", status, "Motivo",
                Instant.parse("2026-11-05T12:00:00Z"), null, 0);
    }

    @BeforeEach void account() {
        UserAccount user = mock(UserAccount.class);
        when(user.id()).thenReturn(owner);
        when(accounts.require(any())).thenReturn(user);
        when(requests.list(any(), any(), anyInt(), anyInt())).thenReturn(new SoftwareRequestPage(List.of(),1,20,0));
    }

    @Test void anonymousAndWrongRolesAreDeniedAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/v1/users/me/software-requests").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/users/me/software-requests").with(anonymous()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/software-requests").with(authentication(token(UserRole.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/me/software-requests").with(authentication(token(UserRole.ADMIN))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users/me/software-requests").with(authentication(token(UserRole.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/software-requests/"+requestId).with(authentication(token(UserRole.ADMIN))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests);
    }

    @Test void personalOperationsAlwaysUseTheCurrentAccountUuid() throws Exception {
        when(requests.create(owner,"Editor","https://example.org")).thenReturn(request("pending"));
        mvc.perform(get("/api/v1/users/me/software-requests").with(authentication(token(UserRole.USER))))
                .andExpect(status().isOk());
        verify(requests).list(owner,null,1,20);
        mvc.perform(post("/api/v1/users/me/software-requests").with(authentication(token(UserRole.USER))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Editor\",\"officialUrl\":\"https://example.org\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"));
        verify(requests).create(owner,"Editor","https://example.org");
    }

    @Test void adminReviewRequiresExplicitVersionAndAuditsSafeSuccessAndFailure() throws Exception {
        var endpoint = "/api/v1/admin/software-requests/"+requestId;
        mvc.perform(patch(endpoint).with(authentication(token(UserRole.USER))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        mvc.perform(patch(endpoint).with(authentication(token(UserRole.ADMIN))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"accepted\",\"reviewNote\":\"Motivo\"}"))
                .andExpect(status().isBadRequest());
        when(requests.review(eq(requestId),eq(owner),any())).thenReturn(request("accepted"));
        String body = "{\"status\":\"accepted\",\"reviewNote\":\"Motivo\",\"version\":0}";
        mvc.perform(patch(endpoint).with(authentication(token(UserRole.ADMIN))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(audit).record(owner.toString(),"software-request.review","software-request",requestId.toString(),
                Map.of("status","accepted","result","success"));
        when(requests.review(eq(requestId),eq(owner),any())).thenThrow(new ConflictException("software_request_changed","Actualiza la lista."));
        mvc.perform(patch(endpoint).with(authentication(token(UserRole.ADMIN))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        verify(audit).record(owner.toString(),"software-request.review","software-request",requestId.toString(),
                Map.of("result","failure","failureType","ConflictException"));
    }
}
