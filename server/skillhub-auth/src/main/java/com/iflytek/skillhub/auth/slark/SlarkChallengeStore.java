package com.iflytek.skillhub.auth.slark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis TTL bounds abandoned challenges; compare-and-delete makes consumption cross-node atomic. */
public class SlarkChallengeStore {
    private static final String PREFIX = "skillhub:slark:challenge:";
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public SlarkChallengeStore(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }
    public record Challenge(String intentId, String clientId, String redirectUri, String stateHash, String cookieHash, long expiresAt) {}
    private String encode(Challenge challenge) {
        try { return json.writeValueAsString(challenge); }
        catch (JsonProcessingException failure) { throw SlarkBridgeFailure.unavailable(); }
    }
    public void save(String id, Challenge challenge) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + id, encode(challenge), Duration.ofSeconds(60))))
            throw SlarkBridgeFailure.unavailable();
    }
    public Challenge get(String id) {
        String value = redis.opsForValue().get(PREFIX + id);
        if (value == null) return null;
        try { return json.readValue(value, Challenge.class); }
        catch (JsonProcessingException failure) { throw SlarkBridgeFailure.unavailable(); }
    }
    public boolean consume(String id, Challenge challenge) {
        return Long.valueOf(1).equals(redis.execute(CONSUME, List.of(PREFIX + id), encode(challenge)));
    }
}
