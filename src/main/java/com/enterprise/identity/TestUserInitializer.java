package com.enterprise.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ✅ Only runs when app.seed.test-users=true — was previously unconditional,
 *    meaning demo accounts were created in production with predictable passwords.
 *
 * Enable locally via:
 *   app.seed.test-users=true
 * and set the env vars TEST_*_EMAIL / TEST_*_PASSWORD.
 */
@Component
@ConditionalOnProperty(name = "app.seed.test-users", havingValue = "true")
public class TestUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestUserInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public TestUserInitializer(UserRepository userRepository,
                               RoleRepository roleRepository,
                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        createTestUser("TEST_CUSTOMER_EMAIL", "TEST_CUSTOMER_PASSWORD", "CUSTOMER");
        createTestUser("TEST_MERCHANT_EMAIL", "TEST_MERCHANT_PASSWORD", "MERCHANT");
        createTestUser("TEST_ADMIN_EMAIL",    "TEST_ADMIN_PASSWORD",    "ADMIN");
        createTestUser("TEST_AUDITOR_EMAIL",  "TEST_AUDITOR_PASSWORD",  "AUDITOR");
    }

    private void createTestUser(String emailEnv, String passwordEnv, String roleName) {
        String email = System.getenv(emailEnv);
        String password = System.getenv(passwordEnv);
        if (email == null || password == null) {
            log.debug("Skipping {} — {} or {} not set", roleName, emailEnv, passwordEnv);
            return;
        }
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
        AppUser user = userRepository.findByEmail(email).orElse(new AppUser());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.getRoles().add(role);
        user.setTenantId(1L);
        user.setActive(true);
        if ("ADMIN".equals(roleName) && "admin@test.com".equals(email)) {
            user.setSuperAdmin(true);
        }
        userRepository.save(user);
        log.info("Seeded test user: {} ({})", email, roleName);
    }
}