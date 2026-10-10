package ao.creativemode.kixi.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServerClockConfig {
    @Bean
    public Clock serverClock() {
        return Clock.systemDefaultZone();
    }
}
