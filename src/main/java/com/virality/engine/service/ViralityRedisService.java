package com.virality.engine.service;

import com.virality.engine.exception.RateLimitException;
import com.virality.engine.exception.UnprocessableEntityException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * ViralityRedisService — Phase 2
 *
 * All guardrail logic lives here. Every check happens BEFORE any PostgreSQL write.
 *
 * ─── Key naming convention ────────────────────────────────────────────────
 *   post:{postId}:virality_score       → INCRBY counter (long)
 *   post:{postId}:bot_count            → INCR counter   (long)
 *   cooldown:bot_{botId}:human_{humanId} → SET NX EX    (presence flag)
 * ─────────────────────────────────────────────────────────────────────────
 *
 * ─── Thread-safety guarantees ─────────────────────────────────────────────
 *   1. Horizontal cap:  Lua script on the Redis server atomically INCR + compare.
 *      The Lua script executes as a single command on the Redis event loop —
 *      no two threads can interleave between the INCR and the cap check.
 *      This guarantees the count never reaches 101 even under 200 concurrent
 *      requests firing simultaneously.
 *
 *   2. Vertical cap:   Pure arithmetic guard (depthLevel > 20) checked before
 *      any I/O. No race condition possible.
 *
 *   3. Cooldown cap:   Redis SET NX EX is a single atomic command. The NX flag
 *      means only one concurrent caller wins the "set" — all others find the
 *      key already set and are rejected with HTTP 429.
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViralityRedisService {

    private final StringRedisTemplate redisTemplate;

    @Value("${virality.notification.bot-reply-score:1}")
    private int botReplyScore;

    @Value("${virality.notification.human-like-score:20}")
    private int humanLikeScore;

    @Value("${virality.notification.human-comment-score:50}")
    private int humanCommentScore;

    @Value("${virality.notification.bot-horizontal-cap:100}")
    private int botHorizontalCap;

    @Value("${virality.notification.depth-vertical-cap:20}")
    private int depthVerticalCap;

    @Value("${virality.notification.bot-cooldown-ttl-seconds:600}")
    private int botCooldownTtlSeconds;

    // ── Lua script: atomic INCR + cap check ──────────────────────────────
    //
    // The script receives:
    //   KEYS[1] = post:{postId}:bot_count
    //   ARGV[1] = cap (100)
    //
    // Returns:
    //   -1 if the cap would be exceeded (key was decremented back atomically)
    //   current value (>= 1) if accepted
    //
    // Because Lua scripts run atomically on the Redis server, no other command
    // can execute between the INCR and the cap check.
    private static final DefaultRedisScript<Long> INCR_WITH_CAP_SCRIPT;

    static {
        INCR_WITH_CAP_SCRIPT = new DefaultRedisScript<>();
        INCR_WITH_CAP_SCRIPT.setResultType(Long.class);
        INCR_WITH_CAP_SCRIPT.setScriptText(
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current > tonumber(ARGV[1]) then " +
            "  redis.call('DECR', KEYS[1]) " +
            "  return -1 " +
            "end " +
            "return current"
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run all three guardrails for a BOT comment.
     * Must be called BEFORE writing the comment to PostgreSQL.
     *
     * @param postId      target post UUID
     * @param botId       bot performing the comment
     * @param humanId     human who owns the post (for cooldown key)
     * @param depthLevel  nesting depth of the comment
     */
    public void applyBotCommentGuardrails(UUID postId, UUID botId,
                                           UUID humanId, int depthLevel) {
        // 1. Vertical cap — pure arithmetic, no I/O needed
        enforceDepthCap(depthLevel);

        // 2. Horizontal cap — atomic Lua script
        enforceHorizontalCap(postId);

        // 3. Cooldown cap — atomic SET NX EX
        enforceCooldownCap(botId, humanId);
    }

    /**
     * Increment virality score for a bot reply (+1).
     * Called AFTER successful DB write.
     */
    public long incrementBotReply(UUID postId) {
        return incrViralityScore(postId, botReplyScore);
    }

    /**
     * Increment virality score for a human like (+20).
     */
    public long incrementHumanLike(UUID postId) {
        return incrViralityScore(postId, humanLikeScore);
    }

    /**
     * Increment virality score for a human comment (+50).
     */
    public long incrementHumanComment(UUID postId) {
        return incrViralityScore(postId, humanCommentScore);
    }

    /**
     * Compensate the horizontal bot_count on DB failure.
     * Called from the service layer catch block to maintain Redis/DB consistency.
     */
    public void decrementBotCount(UUID postId) {
        String key = botCountKey(postId);
        Long current = redisTemplate.opsForValue().decrement(key);
        log.warn("Compensated bot_count for post {} after DB failure. New value: {}", postId, current);
    }

    /**
     * Get the current virality score for a post (returns 0 if key absent).
     */
    public long getViralityScore(UUID postId) {
        String raw = redisTemplate.opsForValue().get(viralityKey(postId));
        return raw == null ? 0L : Long.parseLong(raw);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Guard 1 — vertical depth cap (HTTP 422). */
    private void enforceDepthCap(int depthLevel) {
        if (depthLevel > depthVerticalCap) {
            throw new UnprocessableEntityException(
                String.format("depthLevel %d exceeds maximum allowed depth of %d",
                    depthLevel, depthVerticalCap));
        }
    }

    /**
     * Guard 2 — horizontal bot count cap (HTTP 429).
     *
     * Uses a Lua script that atomically INCRements the counter and rolls it
     * back if the cap is exceeded.  A return value of -1 means "cap exceeded".
     * This is the only way to guarantee the counter never exceeds 100 under
     * concurrent load — a plain INCR + GET + conditional DECR would have a
     * TOCTOU window.
     */
    private void enforceHorizontalCap(UUID postId) {
        String key = botCountKey(postId);
        Long result = redisTemplate.execute(
                INCR_WITH_CAP_SCRIPT,
                List.of(key),
                String.valueOf(botHorizontalCap));

        if (result == null || result == -1L) {
            throw new RateLimitException(
                String.format("Bot horizontal cap (%d) reached for post %s. " +
                    "No more bot comments allowed.", botHorizontalCap, postId));
        }
        log.debug("Bot count for post {} is now {}", postId, result);
    }

    /**
     * Guard 3 — per-bot-per-human cooldown (HTTP 429).
     *
     * SET NX EX is a single atomic Redis command. The NX (Not eXists) flag
     * ensures only one caller can set the key; all simultaneous callers see
     * the key already set and are rejected.
     */
    private void enforceCooldownCap(UUID botId, UUID humanId) {
        String key = cooldownKey(botId, humanId);
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(botCooldownTtlSeconds));
        if (Boolean.FALSE.equals(set)) {
            throw new RateLimitException(
                String.format("Cooldown active for bot %s on human %s. " +
                    "Retry after %d seconds.", botId, humanId, botCooldownTtlSeconds));
        }
        log.debug("Cooldown set for bot {} / human {} (TTL={}s)", botId, humanId, botCooldownTtlSeconds);
    }

    /** Atomically add delta to the virality score and return the new total. */
    private long incrViralityScore(UUID postId, int delta) {
        String key = viralityKey(postId);
        Long newScore = redisTemplate.opsForValue().increment(key, delta);
        long score = newScore == null ? 0L : newScore;
        log.debug("Virality score for post {} → {} (delta +{})", postId, score, delta);
        return score;
    }

    // ─────────────────────────────────────────────────────────────────────
    // KEY BUILDERS
    // ─────────────────────────────────────────────────────────────────────

    public static String viralityKey(UUID postId) {
        return "post:" + postId + ":virality_score";
    }

    public static String botCountKey(UUID postId) {
        return "post:" + postId + ":bot_count";
    }

    public static String cooldownKey(UUID botId, UUID humanId) {
        return "cooldown:bot_" + botId + ":human_" + humanId;
    }
}
