package ao.creativemode.kixi.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * First-ADMIN bootstrap credentials, from the environment:
 * {@code APP_BOOTSTRAP_ADMIN_USERNAME}, {@code APP_BOOTSTRAP_ADMIN_EMAIL},
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD} (plus optional
 * {@code APP_BOOTSTRAP_ADMIN_FIRST_NAME} / {@code _LAST_NAME}).
 *
 * <p>All blank by default: without them {@link AdminBootstrap} does nothing.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.bootstrap.admin")
public class AdminBootstrapProperties {

    private String username;
    private String email;
    private String password;
    private String firstName;
    private String lastName;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /** True when all three required values are present. */
    public boolean isConfigured() {
        return username != null && !username.isBlank()
                && email != null && !email.isBlank()
                && password != null && !password.isBlank();
    }
}
