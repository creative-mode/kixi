package ao.creativemode.kixi.shared.config;

import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestIdConfig {

    @Bean
    public RequestIdWebFilter requestIdWebFilter() {
        return new RequestIdWebFilter();
    }
}
