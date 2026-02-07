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

		app.setDefaultProperties(properties);
		app.run(args);
	}

}