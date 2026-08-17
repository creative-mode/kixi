package ao.creativemode.kixi.config;

import ao.creativemode.kixi.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers("/api/v1/accounts", "/api/v1/accounts/**",
                                "/api/v1/users", "/api/v1/users/**",
                                "/api/v1/roles", "/api/v1/roles/**",
                                "/api/v1/sessions", "/api/v1/sessions/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/statements/**")
                        .authenticated()
                        .pathMatchers("/api/v1/statements/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.GET, "/api/v1/ocr/**")
                        .authenticated()
                        .pathMatchers("/api/v1/ocr/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/school-years", "/api/v1/school-years/**",
                                "/api/v1/terms", "/api/v1/terms/**",
                                "/api/v1/subjects", "/api/v1/subjects/**",
                                "/api/v1/courses", "/api/v1/courses/**",
                                "/api/v1/classes", "/api/v1/classes/**",
                                "/api/v1/question-images", "/api/v1/question-images/**")
                        .authenticated()
                        .pathMatchers("/api/v1/school-years", "/api/v1/school-years/**",
                                "/api/v1/terms", "/api/v1/terms/**",
                                "/api/v1/subjects", "/api/v1/subjects/**",
                                "/api/v1/courses", "/api/v1/courses/**",
                                "/api/v1/classes", "/api/v1/classes/**",
                                "/api/v1/question-images", "/api/v1/question-images/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.GET, "/api/v1/simulation-answers/**",
                                "/api/simulations/**")
                        .authenticated()
                        .pathMatchers("/api/v1/simulation-answers/**", "/api/simulations/**")
                        .hasAnyRole("ADMIN", "TEACHER", "STUDENT")
                        .anyExchange().authenticated()
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                )
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
