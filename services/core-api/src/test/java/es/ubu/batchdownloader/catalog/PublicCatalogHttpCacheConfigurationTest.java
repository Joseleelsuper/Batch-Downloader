package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** Comprueba que las cabeceras públicas no se pierden por la identidad anónima de Spring Security. */
class PublicCatalogHttpCacheConfigurationTest {
    /** Un visitante anónimo puede reutilizar brevemente las respuestas públicas. */
    @Test
    void anonymousAuthenticationUsesPublicCacheControl() throws Exception {
        MockHttpServletRequest request = catalogRequest();
        request.setUserPrincipal(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletResponse response = applyFilter(request);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("public, max-age=5, stale-while-revalidate=15");
    }

    /** Una sesión autenticada conserva la prohibición de caché compartida. */
    @Test
    void authenticatedRequestUsesPrivateNoStore() throws Exception {
        MockHttpServletRequest request = catalogRequest();
        request.setUserPrincipal(new UsernamePasswordAuthenticationToken(
                "account", "password", AuthorityUtils.createAuthorityList("ROLE_USER")));
        MockHttpServletResponse response = applyFilter(request);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    }

    /** Crea una lectura representativa del catálogo. */
    private static MockHttpServletRequest catalogRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/apps");
        request.setServletPath("/api/v1/apps");
        return request;
    }

    /** Ejecuta el filtro registrado sobre una respuesta correcta. */
    private static MockHttpServletResponse applyFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new PublicCatalogHttpCacheConfiguration()
                .publicCacheControlFilter()
                .getFilter()
                .doFilter(request, response, (servletRequest, servletResponse) -> {
                    servletResponse.setContentType("application/json");
                    servletResponse.getWriter().write("{}");
                    servletResponse.flushBuffer();
                });
        return response;
    }
}
