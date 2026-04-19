package com.virality.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration.
 *
 * We use StringRedisTemplate throughout the service because all our keys and
 * values are strings (longs are converted with Long.parseLong / String.valueOf).
 * This avoids JdkSerializationRedisSerializer byte-prefix issues and keeps the
 * Redis data human-readable in redis-cli.
 */
@Configuration
public class RedisConfig {

    /**
     * Primary template used by ViralityRedisService.
     * Keys = Strings, Values = Strings.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Generic RedisTemplate<String, String> — also wired with String serializers.
     * Exposed as a separate bean so other components can inject it by type if needed.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
