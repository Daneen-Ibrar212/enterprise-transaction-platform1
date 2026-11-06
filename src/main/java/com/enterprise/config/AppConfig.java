package com.enterprise.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplateBuilder()
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ✅ The hardcoded dummy JavaMailSender bean has been REMOVED.
    //    It shadowed Spring Boot's auto-configured bean, so every real
    //    send failed silently against smtp.example.com with dummy creds.
    //
    //    Configure email via application.yml:
    //      spring.mail.host, spring.mail.port, spring.mail.username,
    //      spring.mail.password
    //
    //    In dev, use spring.mail.host=localhost and run MailHog / Mailpit.
}