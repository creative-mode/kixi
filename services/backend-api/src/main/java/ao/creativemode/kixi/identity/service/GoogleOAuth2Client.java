package ao.creativemode.kixi.identity.service;

import ao.creativemode.kixi.identity.config.GoogleOAuth2Properties;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Cliente para trocar code por token e obter userinfo do Google OAuth2.
 */
@Component
public class GoogleOAuth2Client {

    private final GoogleOAuth2Properties properties;
    private final WebClient webClient = WebClient.builder().build();

    public GoogleOAuth2Client(GoogleOAuth2Properties properties) {
        this.properties = properties;
    }

    public Mono<String> exchangeCodeForAccessToken(String code) {
        if (properties.getClientId() == null || properties.getClientSecret() == null) {
            return Mono.error(new IllegalStateException("Google OAuth2 not configured (clientId/clientSecret missing)"));
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", properties.getRedirectUri());
        form.add("grant_type", "authorization_code");

        return webClient.post()
                .uri(properties.getTokenUri())
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (String) m.get("access_token"))
                .filter(t -> t != null && !t.isBlank())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid or expired code")));
    }

    public Mono<GoogleUserInfo> getUserInfo(String accessToken) {
        return webClient.get()
                .uri(properties.getUserInfoUri())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> {
                    Object verifiedClaim = m.get("email_verified");
                    if (verifiedClaim == null) {
                        verifiedClaim = m.get("verified_email");
                    }
                    return new GoogleUserInfo(
                            firstString(m, "sub", "id"),
                            (String) m.get("email"),
                            Boolean.TRUE.equals(verifiedClaim)
                                    || "true".equalsIgnoreCase(String.valueOf(verifiedClaim)),
                            (String) m.get("name"),
                            (String) m.get("picture")
                    );
                });
    }

    private static String firstString(Map<?, ?> claims, String primary, String fallback) {
        Object value = claims.get(primary);
        if (!(value instanceof String text) || text.isBlank()) {
            value = claims.get(fallback);
        }
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    public record GoogleUserInfo(String sub, String email, boolean emailVerified, String name, String picture) {
        public GoogleUserInfo(String email, String name, String picture) {
            this(null, email, false, name, picture);
        }
    }
}
