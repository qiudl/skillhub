package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/** Uses only an explicitly selected dedicated loopback Redis; no production credentials. */
@EnabledIfSystemProperty(named = "slark.bridge.test.redis-port", matches = "[0-9]+")
class SlarkChallengeStoreTest {
    @Test void realRedisHasTtlAndAtomicCompareDeleteAcrossConcurrentConsumers() throws Exception {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("slark.bridge.test.redis-port")));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        var store = new SlarkChallengeStore(redis, new ObjectMapper());
        String id = UUID.randomUUID().toString();
        String key = "skillhub:slark:challenge:" + id;
        var challenge = new SlarkChallengeStore.Challenge("intent", "slark", "https://skills.example/callback", "state-hash", "cookie-hash", System.currentTimeMillis() + 60000);
        try {
            store.save(id, challenge);
            assertThat(redis.getExpire(key)).isBetween(1L, 60L);
            assertThat(store.get(id)).isEqualTo(challenge);
            assertThat(store.consume(id, new SlarkChallengeStore.Challenge("other", "slark", challenge.redirectUri(), "state-hash", "cookie-hash", challenge.expiresAt()))).isFalse();
            var wins = new AtomicInteger();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 16; i++) executor.submit(() -> { if (store.consume(id, challenge)) wins.incrementAndGet(); });
            }
            assertThat(wins).hasValue(1);
            assertThat(store.get(id)).isNull();
        } finally { redis.delete(key); factory.destroy(); }
    }
}
