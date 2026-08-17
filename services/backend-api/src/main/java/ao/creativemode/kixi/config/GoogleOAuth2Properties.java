package ao.creativemode.kixi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth.google")
public class GoogleOAuth2Properties {

    private String clientId;
    private String clientSecret;
    private String redirectUri = "http://localhost:8080/api/v1/auth/google/callback";
    private String authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth";
    private String tokenUri = "https://oauth2.googleapis.com/token";
    private String userInfoUri = "https://www.googleapis.com/oauth2/v2/userinfo";
    private String scope = "openid email profile";
    private boolean stateCookieSecure = true;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAuthorizationUri() {
        return authorizationUri;
    }

    public void setAuthorizationUri(String authorizationUri) {
        this.authorizationUri = authorizationUri;
    }

    public String getTokenUri() {
        return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    public String getUserInfoUri() {
        return userInfoUri;
    }

    public void setUserInfoUri(String userInfoUri) {
        this.userInfoUri = userInfoUri;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public boolean isStateCookieSecure() {
        return stateCookieSecure;
    }

    public void setStateCookieSecure(boolean stateCookieSecure) {
        this.stateCookieSecure = stateCookieSecure;
    }

    public String buildAuthorizationUrl(String state) {
        return authorizationUri + "?client_id=" + clientId
                + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&scope=" + java.net.URLEncoder.encode(scope, java.nio.charset.StandardCharsets.UTF_8)
                + (state != null ? "&state=" + state : "");
    }
}
