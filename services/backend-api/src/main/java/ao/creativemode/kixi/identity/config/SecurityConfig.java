package ao.creativemode.kixi.identity.config;

import ao.creativemode.kixi.identity.security.JwtAuthenticationFilter;
import ao.creativemode.kixi.shared.security.RequestIdWebFilter;
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
import org.springframework.web.cors.reactive.CorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RequestIdWebFilter requestIdWebFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RequestIdWebFilter requestIdWebFilter,
                          CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.requestIdWebFilter = requestIdWebFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        // API documentation (springdoc): schema público, não expõe
                        // dados. Pode ser desligado via SPRINGDOC_API_DOCS_ENABLED /
                        // SPRINGDOC_SWAGGER_UI_ENABLED (false fora de dev).
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**", "/webjars/**").permitAll()
                        .pathMatchers("/api/v1/accounts", "/api/v1/accounts/**",
                                "/api/v1/users", "/api/v1/users/**",
                                "/api/v1/roles", "/api/v1/roles/**",
                                "/api/v1/teachers", "/api/v1/teachers/**",
                                "/api/v1/sessions", "/api/v1/sessions/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/institutions/mine")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/institutions/trash")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/institutions",
                                "/api/v1/institutions/*",
                                "/api/v1/institutions/*/subjects")
                        .authenticated()
                        .pathMatchers("/api/v1/institutions", "/api/v1/institutions/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/teaching-assignments/me")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers("/api/v1/teaching-assignments", "/api/v1/teaching-assignments/**")
                        .hasRole("ADMIN")
                        .pathMatchers("/api/v1/enrollments", "/api/v1/enrollments/**",
                                "/api/v1/me", "/api/v1/me/**")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/statements/review",
                                "/api/v1/statements/from-ocr",
                                "/api/v1/statements/trash",
                                "/api/v1/statements/stats")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.GET, "/api/v1/statements/**")
                        .authenticated()
                        .pathMatchers("/api/v1/statements/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        // The answer key also answers to the path the issue
                        // spells out, /api/v1/questions/{id}/correct-option,
                        // which is outside the statements tree. Without a rule
                        // of its own it fell through to anyExchange(), and any
                        // authenticated account — a student included — could
                        // mark an answer. Nothing in the service would have
                        // stopped it: the write rule weighs the statement the
                        // caller never named.
                        .pathMatchers("/api/v1/questions/**")
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
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/simulation-answers/trash",
                                "/api/v1/simulations/trash",
                                "/api/simulations/trash")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/simulation-answers",
                                "/api/v1/simulation-answers/**",
                                "/api/v1/simulations",
                                "/api/v1/simulations/**",
                                "/api/simulations",
                                "/api/simulations/**")
                        .authenticated()
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/simulation-answers/*/restore")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/simulation-answers",
                                "/api/v1/simulation-answers/**",
                                "/api/v1/simulations",
                                "/api/v1/simulations/**",
                                "/api/simulations",
                                "/api/simulations/**")
                        .hasAnyRole("ADMIN", "TEACHER", "STUDENT")
                        .pathMatchers(HttpMethod.PUT,
                                "/api/v1/simulations/*/restore",
                                "/api/simulations/*/restore")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.PUT,
                                "/api/v1/simulation-answers/*",
                                "/api/v1/simulations/*",
                                "/api/simulations/*")
                        .hasAnyRole("ADMIN", "TEACHER", "STUDENT")
                        .pathMatchers("/api/v1/simulation-answers", "/api/v1/simulation-answers/**",
                                "/api/v1/simulations", "/api/v1/simulations/**",
                                "/api/simulations", "/api/simulations/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .anyExchange().authenticated()
                )
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                )
                .addFilterAt(requestIdWebFilter, SecurityWebFiltersOrder.FIRST)
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
