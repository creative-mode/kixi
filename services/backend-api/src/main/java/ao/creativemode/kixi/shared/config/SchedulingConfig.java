package ao.creativemode.kixi.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns Spring's scheduler on for the application. It lives here, in the
 * kernel, so any module can declare {@code @Scheduled} work without this
 * class having to know anything about that module.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}