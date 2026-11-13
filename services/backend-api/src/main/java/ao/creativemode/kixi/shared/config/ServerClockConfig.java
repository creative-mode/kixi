package ao.creativemode.kixi.shared.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServerClockConfig {
    public static final ZoneId ZONE = ZoneId.of("Africa/Luanda");
    @Bean
    public Clock serverClock() {
        return Clock.system(ZONE);
    }
}
