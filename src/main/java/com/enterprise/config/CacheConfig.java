package com.enterprise.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Minimal in-memory cache. No size limits or expiry — swap for Caffeine
     * (see P0-5 notes) or Redis before running at scale.
     *
     * Two caches are declared so the @Cacheable(value="...") annotations in
     * ExchangeRateService and FeatureFlagService have somewhere to land.
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("exchangeRates", "featureFlags");
    }
}