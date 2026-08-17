package ao.creativemode.kixi.config;

import ao.creativemode.kixi.security.RequestIdWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestIdConfig {

    @Bean
    public RequestIdWebFilter requestIdWebFilter() {
        return new RequestIdWebFilter();
    }
}
