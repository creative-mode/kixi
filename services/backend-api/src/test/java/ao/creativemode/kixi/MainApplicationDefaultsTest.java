package ao.creativemode.kixi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainApplicationDefaultsTest {

    @Test
    void theApiMapIsOffWhenNobodyAskedForIt() {
        Map<String, Object> properties = new HashMap<>();

        MainApplication.applyApiDocsDefaults(properties);

        assertThat(properties).containsEntry("springdoc.api-docs.enabled", "false")
                .containsEntry("springdoc.swagger-ui.enabled", "false");
    }

    @Test
    void aDotenvThatTurnsItOnIsHonoured() {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "SPRINGDOC_API_DOCS_ENABLED", "true", "SPRINGDOC_SWAGGER_UI_ENABLED", "true"));

        MainApplication.applyApiDocsDefaults(properties);

        assertThat(properties).containsEntry("springdoc.api-docs.enabled", "true")
                .containsEntry("springdoc.swagger-ui.enabled", "true");
    }
}
