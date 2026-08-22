package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorControllerTest {
    private final ApiErrorController controller = new ApiErrorController();

    @Test
    void preservesTheStatusAndReturnsASafeJsonContract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 503);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/example");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret"));

        var response = controller.error(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "unexpected_error",
                "No se pudo completar la solicitud",
                java.util.Map.of("path", "/api/v1/example")));
        assertThat(response.getBody().message()).doesNotContain("secret");
    }

    @Test
    void returnsNotFoundForAnUnknownRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        var response = controller.error(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("not_found");
        assertThat(response.getBody().details()).isEmpty();
    }
}
