package es.ubu.batchdownloader.identity.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Permite arrancar Core aunque Google OIDC no esté configurado. */
@Configuration
class GoogleOAuthClientConfiguration {
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.oauth.google.client-id:}") String clientId,
            @Value("${app.oauth.google.client-secret:}") String clientSecret,
            @Value("${app.oauth.google.redirect-uri}") String redirectUri) {
        ClientRegistration google = configured(clientId, clientSecret)
                ? CommonOAuth2Provider.GOOGLE.getBuilder("google")
                        .clientId(clientId.strip())
                        .clientSecret(clientSecret.strip())
                        .scope("openid", "email")
                        .redirectUri(redirectUri)
                        .build()
                : null;
        return registrationId -> "google".equals(registrationId) ? google : null;
    }

    private static boolean configured(String clientId, String clientSecret) {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
