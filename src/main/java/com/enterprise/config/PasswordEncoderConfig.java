package com.enterprise.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PasswordEncoder lives in its own @Configuration class so that beans which
 * depend on it (ApiKeyService, UserDetailsService, etc.) don't have to pull
 * in SecurityConfig, which itself depends on the security filter chain that
 * transitively depends on those services.
 *
 * Bean cycle (fixed by this class):
 *
 *   SecurityConfig → ApiKeyAuthenticationFilter → ApiKeyService
 *                                                       ↓
 *                                                  PasswordEncoder
 *                                                       ↑
 *                            (was: defined inside SecurityConfig)
 *
 * Before this class existed, ApiKeyService demanded a PasswordEncoder that
 * only SecurityConfig could provide — but SecurityConfig couldn't be
 * constructed until ApiKeyAuthenticationFilter existed, which needed
 * ApiKeyService, which needed SecurityConfig. Circular.
 *
 * Moving PasswordEncoder here makes it a leaf bean with no dependencies,
 * so the cycle disappears.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}