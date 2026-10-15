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
                        // #252: the donation form is public, and only the submission: everything else
                        // under /donations (list, files, review, extract, link) is ADMIN below. The
                        // method and the exact path are named so no other route opens by accident.
                        .pathMatchers(HttpMethod.POST, "/api/v1/donations").permitAll()
                        .pathMatchers("/api/v1/donations", "/api/v1/donations/**").hasRole("ADMIN")
                        // API documentation (springdoc): schema público, não expõe
                        // dados. Pode ser desligado via SPRINGDOC_API_DOCS_ENABLED /
                        // SPRINGDOC_SWAGGER_UI_ENABLED (false fora de dev).
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**", "/webjars/**").permitAll()
                        // #176: a teacher reads their own record; every other
                        // /teachers route stays ADMIN-only below.
                        .pathMatchers(HttpMethod.GET, "/api/v1/teachers/me")
                        .hasAnyRole("ADMIN", "TEACHER")
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
                        // #206: a teacher writes the tutor style of their own
                        // assignments; the service checks the ownership.
                        .pathMatchers(HttpMethod.PUT, "/api/v1/teaching-assignments/*/tutor-style")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers("/api/v1/teaching-assignments", "/api/v1/teaching-assignments/**")
                        .hasRole("ADMIN")
                        .pathMatchers("/api/v1/enrollments", "/api/v1/enrollments/**",
                                "/api/v1/me", "/api/v1/me/**")
                        .authenticated()
                        // The AI tutor (issue #114). Every signed-in account may
                        // chat; the service scopes each turn to the caller's own
                        // session and to that session's visible statement. An
                        // explicit rule keeps this path out of anyExchange() —
                        // the fall-through that twice let a student reach
                        // material it should not (teaching-assignments, gabarito).
                        .pathMatchers("/api/v1/chat/**")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/exam-rooms/mine", "/api/exam-rooms/mine",
                                "/api/v1/exam-rooms/*/student", "/api/exam-rooms/*/student")
                        .authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/v1/exam-rooms/*/join", "/api/exam-rooms/*/join")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/v1/exam-rooms/*/live", "/api/exam-rooms/*/grades",
                                "/api/v1/exam-rooms/*/grades.csv", "/api/exam-rooms/*/live",
                                "/api/exam-rooms/*/grades", "/api/exam-rooms/*/grades.csv")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers("/api/v1/exam-rooms", "/api/v1/exam-rooms/**",
                                "/api/exam-rooms", "/api/exam-rooms/**")
                        .hasAnyRole("ADMIN", "TEACHER")
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
                        // Trash views expose records the normal catalog hides.
                        // Keep them staff-only before the authenticated read rules.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/school-years/trash", "/api/v1/terms/trash",
                                "/api/v1/subjects/trash", "/api/v1/courses/trash",
                                "/api/v1/classes/trash", "/api/v1/question-images/trash")
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
                        // Social learning (issue #119): revoking is an explicit act
                        // of the simulation's owner, and without its own rule the
                        // DELETE would fall through to the staff-only catch-all
                        // and lock students out of revoking their own consent.
                        // (The POST is already covered by the three-role
                        // simulations rule below.)
                        .pathMatchers(HttpMethod.DELETE,
                                "/api/v1/simulations/*/share")
                        .hasAnyRole("ADMIN", "TEACHER", "STUDENT")
                        .pathMatchers(HttpMethod.PATCH,
                                "/api/v1/simulation-answers/*/grade")
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
                        // Dashboard (BE-14): staff only. What each one sees is narrowed
                        // by AnalyticsService, from the account, not from a parameter.
                        .pathMatchers("/api/v1/analytics", "/api/v1/analytics/**")
                        .hasAnyRole("ADMIN", "TEACHER")
                        // The relative ranking (issue #117). Any signed-in account may
                        // read it; the cohort comes from /me and never from a query
                        // parameter, so what keeps a ranking relative is the service,
                        // not this rule. It is spelled out because leaving it to
                        // anyExchange() would work by accident, and the two bugs this
                        // file already carries were both accidents of the same kind.
                        .pathMatchers("/api/v1/leaderboard", "/api/v1/leaderboard/**")
                        .authenticated()
                        // Social feed (issue #120). Hiding, un-hiding and the
                        // reported-post queue are moderation and stay out of
                        // anyExchange(): a role rule here, not inside the
                        // service alone, is what stops a student reaching
                        // them at all rather than being refused by business
                        // logic after the fact.
                        .pathMatchers(HttpMethod.GET, "/api/v1/feed/posts/reported")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers(HttpMethod.PATCH,
                                "/api/v1/feed/posts/*/hide",
                                "/api/v1/feed/posts/*/unhide")
                        .hasAnyRole("ADMIN", "TEACHER")
                        .pathMatchers("/api/v1/feed/**")
                        .authenticated()
                        // Friendships (issue #121): any signed-in account, for the same
                        // reason the ranking is. What a request may do is decided by who
                        // is holding the other end of it, not by a role, and a teacher
                        // follows a colleague exactly like a student does.
                        .pathMatchers("/api/v1/friends", "/api/v1/friends/**")
                        .authenticated()
                        // Unknown routes fail closed. A new controller must
                        // receive an explicit rule above before any role can
                        // reach it.
                        .anyExchange().denyAll()
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
