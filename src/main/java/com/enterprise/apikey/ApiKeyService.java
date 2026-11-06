package com.enterprise.apikey;

import com.enterprise.identity.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private static final String API_KEY_PREFIX = "ep_";
    private static final int KEY_LENGTH = 32;
    private static final int PREFIX_LENGTH = 8;
    private static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ApiKeyRepository apiKeyRepository;

    /**
     * ✅ INJECTED from PasswordEncoderConfig — do NOT use `new BCryptPasswordEncoder()`
     *    here. Instantiating directly was the original bug: it bypassed the bean
     *    and made the ApiKeyService "own" an encoder that Spring didn't know about,
     *    which then caused the bean-cycle if passwordEncoder moved into SecurityConfig.
     */
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         PasswordEncoder passwordEncoder) {
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ApiKey generateApiKey(AppUser user, String name) {
        String fullKey = generateSecureKey();
        String sha = sha256Hex(fullKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyValue(maskKey(fullKey));
        apiKey.setKeyHashSha256(sha);
        apiKey.setKeyHash(passwordEncoder.encode(fullKey));
        apiKey.setKeyPrefix(fullKey.substring(0, PREFIX_LENGTH));
        apiKey.setName(name);
        apiKey.setUser(user);
        apiKey.setActive(true);
        apiKey.setTenantId(user.getTenantId());

        ApiKey saved = apiKeyRepository.save(apiKey);
        log.info("Generated API key {} for user {}", saved.getKeyPrefix(), user.getEmail());
        return saved;
    }

    private static String maskKey(String fullKey) {
        if (fullKey == null || fullKey.length() < 12) return "****";
        return fullKey.substring(0, 8) + "-****-****-" + fullKey.substring(fullKey.length() - 4);
    }

    @Transactional
    public void revokeApiKey(Long keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API Key not found"));
        key.setActive(false);
        apiKeyRepository.save(key);
        log.info("Revoked API key: {}", key.getKeyPrefix());
    }

    /**
     * ✅ Fast path: SHA-256 lookup → single BCrypt verify.
     *    Previously iterated every active key with BCrypt (O(N) × 100ms).
     */
    public Optional<ApiKey> validateKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return Optional.empty();
        }
        String sha = sha256Hex(rawKey);
        Optional<ApiKey> candidate = apiKeyRepository.findByKeyHashSha256Global(sha);
        if (candidate.isEmpty()) {
            log.debug("API key not found by SHA-256");
            return Optional.empty();
        }
        ApiKey key = candidate.get();

        // Secondary verification with BCrypt
        if (!passwordEncoder.matches(rawKey, key.getKeyHash())) {
            log.warn("SHA-256 matched but BCrypt did not on key {}", key.getKeyPrefix());
            return Optional.empty();
        }

        if (!key.isActive()) {
            log.debug("API key is inactive: {}", key.getKeyPrefix());
            return Optional.empty();
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.debug("API key expired: {}", key.getKeyPrefix());
            return Optional.empty();
        }
        return Optional.of(key);
    }

    @Transactional
    public void updateLastUsed(ApiKey apiKey) {
        apiKey.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(apiKey);
    }

    public List<ApiKey> getAllKeys() {
        return apiKeyRepository.findAll();
    }

    private String generateSecureKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(secureRandom.nextInt(CHARACTERS.length())));
        }
        return API_KEY_PREFIX + sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}