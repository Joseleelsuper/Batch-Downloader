package es.ubu.batchdownloader.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Punto de entrada estable para conservar un destino interno tras Google OIDC. */
@Controller
@RequestMapping("/api/v1/auth/oauth2")
public class OAuthLoginController {
    public static final String RETURN_TO_SESSION_ATTRIBUTE =
            OAuthLoginController.class.getName() + ".RETURN_TO";

    @GetMapping("/google")
    public void google(
            @RequestParam(required = false) String returnTo,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(true);
        session.setAttribute(RETURN_TO_SESSION_ATTRIBUTE, safeReturnTo(returnTo));
        response.sendRedirect(request.getContextPath()
                + "/api/v1/auth/oauth2/authorization/google");
    }

    public static String safeReturnTo(String value) {
        if (value == null || value.isBlank()) return "/dashboard";
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("\\")) {
            return "/dashboard";
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return "/dashboard";
            if (value.charAt(index) == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    int decoded = (high << 4) + low;
                    if (decoded <= 31 || decoded == 127 || decoded == '\\' || decoded == '%'
                            || (index == 1 && decoded == '/')) {
                        return "/dashboard";
                    }
                }
            }
        }
        return value;
    }
}
