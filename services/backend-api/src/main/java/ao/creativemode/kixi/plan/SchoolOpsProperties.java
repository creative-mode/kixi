package ao.creativemode.kixi.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A ligação ao motor de pautas SchoolOps (kixi#525). Desligada por omissão: sem {@code schoolops.enabled=true} nada é enviado.
 * O token e o segredo dos eventos vêm só da configuração do servidor.
 */
@Component
@ConfigurationProperties(prefix = "schoolops")
public class SchoolOpsProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String token = "";
    private String eventsSecret = "";
    private String callbackUrl = "";

    public boolean isEnabled() {
        return enabled && !baseUrl.isBlank() && !token.isBlank() && !callbackUrl.isBlank();
    }

    public boolean isEventsConfigured() {
        return !eventsSecret.isBlank();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEventsSecret() {
        return eventsSecret;
    }

    public void setEventsSecret(String eventsSecret) {
        this.eventsSecret = eventsSecret;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }
}
