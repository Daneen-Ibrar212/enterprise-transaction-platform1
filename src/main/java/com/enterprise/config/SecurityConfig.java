package com.enterprise.config;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.identity.UserRepository;
import com.enterprise.security.ApiKeyAuthenticationFilter;
import com.enterprise.security.AuthenticationFailureHandler;
import com.enterprise.security.CustomAuthenticationSuccessHandler;
import com.enterprise.security.CustomPermissionEvaluator;
import com.enterprise.security.LogoutSuccessHandler;
import com.enterprise.security.RateLimitingFilter;
import com.enterprise.security.TwoFactorAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final CustomPermissionEvaluator customPermissionEvaluator;
    private final CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler;
    private final AuthenticationFailureHandler authenticationFailureHandler;
    private final LogoutSuccessHandler logoutSuccessHandler;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;
    private final Environment environment;

    public SecurityConfig(CustomPermissionEvaluator customPermissionEvaluator,
                          CustomAuthenticationSuccessHandler customAuthenticationSuccessHandler,
                          AuthenticationFailureHandler authenticationFailureHandler,
                          LogoutSuccessHandler logoutSuccessHandler,
                          ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                          RateLimitingFilter rateLimitingFilter,
                          FeatureFlagService featureFlagService,
                          UserRepository userRepository,
                          Environment environment) {
        this.customPermissionEvaluator = customPermissionEvaluator;
        this.customAuthenticationSuccessHandler = customAuthenticationSuccessHandler;
        this.authenticationFailureHandler = authenticationFailureHandler;
        this.logoutSuccessHandler = logoutSuccessHandler;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
        this.environment = environment;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/woocommerce/**").permitAll()
                .requestMatchers("/api/notifications/stream").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitingFilter, ApiKeyAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain uiFilterChain(HttpSecurity http) throws Exception {
        boolean isDev = Arrays.asList(environment.getActiveProfiles()).contains("dev");

        http
            .securityMatcher("/**")
            .headers(headers -> headers
                .addHeaderWriter(new StaticHeadersWriter("Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                        "default-src 'self'; " +
                        "script-src 'self' 'unsafe-inline' 'unsafe-eval' " +
                            "https://cdn.jsdelivr.net " +
                            "https://cdnjs.cloudflare.com " +
                            "https://unpkg.com; " +
                        "style-src 'self' 'unsafe-inline' " +
                            "https://fonts.googleapis.com " +
                            "https://cdn.jsdelivr.net; " +
                        "font-src 'self' data: " +
                            "https://fonts.gstatic.com " +
                            "https://cdn.jsdelivr.net; " +
                        "img-src 'self' data: https:; " +
                        "connect-src 'self' " +
                            "https://cdn.jsdelivr.net " +
                            "https://fonts.googleapis.com " +
                            "https://fonts.gstatic.com " +
                            "https://unpkg.com; " +
                        "frame-src 'self'; " +
                        "object-src 'none'; " +
                        "base-uri 'self'; " +
                        "form-action 'self';"
                    )
                )
                .frameOptions(frame -> frame.deny())
                .addHeaderWriter(new StaticHeadersWriter("X-XSS-Protection", "1; mode=block"))
                .addHeaderWriter(new StaticHeadersWriter("X-Content-Type-Options", "nosniff"))
                .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "strict-origin-when-cross-origin"))
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                    "/api/**",
                    "/admin/**",
                    "/login",
                    "/invoices/**",
                    "/pay/**",
                    "/webhooks/**"
                )
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/login",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/fonts/**",
                    "/favicon.ico",
                    "/webjars/**",
                    "/error"
                ).permitAll()
                .requestMatchers(
                    "/health/**",
                    "/pay/**",
                    "/test/email",
                    "/2fa/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/metrics",
                    "/actuator/prometheus",
                    "/notifications/stream"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(customAuthenticationSuccessHandler)
                .failureHandler(authenticationFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessHandler(logoutSuccessHandler)
                .permitAll()
            )
            .addFilterAfter(
                new TwoFactorAuthenticationFilter(featureFlagService, userRepository),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(customPermissionEvaluator);
        return handler;
    }
}