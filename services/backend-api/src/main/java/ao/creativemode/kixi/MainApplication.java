package ao.creativemode.kixi;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class MainApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.configure()
				.directory("./services/backend-api")
				.ignoreIfMissing()
				.load();

		SpringApplication app = new SpringApplication(MainApplication.class);

		Map<String, Object> properties = new HashMap<>();
		dotenv.entries().forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
		applyApiDocsDefaults(properties);

		app.setDefaultProperties(properties);
		app.run(args);
	}

	/**
	 * The API map (Swagger) is off unless someone turns it on: a fresh install that forgets the two variables
	 * no longer publishes it. A default has the lowest precedence, so the environment still wins; the .env
	 * names are mapped to the canonical keys because a default-property map does no relaxed binding.
	 */
	static void applyApiDocsDefaults(Map<String, Object> properties) {
		properties.putIfAbsent("springdoc.api-docs.enabled",
				properties.getOrDefault("SPRINGDOC_API_DOCS_ENABLED", "false"));
		properties.putIfAbsent("springdoc.swagger-ui.enabled",
				properties.getOrDefault("SPRINGDOC_SWAGGER_UI_ENABLED", "false"));
	}

}