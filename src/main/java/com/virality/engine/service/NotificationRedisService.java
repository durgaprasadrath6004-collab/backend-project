package com.virality.engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * NotificationRedisService — Phase 3 (throttler half)
 *
 * Implements the two-phase notification logic:
 *   1. If notif_cooldown:{userId} is absent  → send immediate push + set cooldown
 *   2. If notif_cooldown:{userId} is present → queue into user:{userId}:pending_notifs
 *
 * Key naming:
 *   notif_cooldown:{userId}       → presence flag (STRING, TTL=900s)
 *   user:{userId}:pending_notifs  → List of pending notification messages
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRedisService {

    private final StringRedisTemplate redisTemplate;

    @Value("${virality.notification.notif-cooldown-ttl-seconds:900}")
    private int notifCooldownTtlSeconds;

    // ── Public key helpers (also used by the sweeper) ────────────────────

    public static String notifCooldownKey(UUID userId) {
        return "notif_cooldown:" + userId;
    }

    public static String pendingNotifsKey(UUID userId) {
        return "user:" + userId + ":pending_notifs";
    }

    // ── Main notification entrypoint ──────────────────────────────────────

    /**
     * Called after a bot successfully comments on a human's post.
     *
     * Race-condition note: setIfAbsent uses SET NX EX internally — atomic.
     * Two concurrent bot interactions for the same userId cannot both win the
     * first-notification slot.  One will send the push, the other will enqueue.
     *
     * @param userId  owner of the post
     * @param botName name of the bot that just interacted
     */
    public void handleBotInteraction(UUID userId, String botName) {
        String cooldownKey   = notifCooldownKey(userId);
        String pendingKey    = pendingNotifsKey(userId);
        String message       = buildMessage(botName, userId);

        // Atomic SET NX EX: returns true only if key was absent (we won the slot)
        Boolean isFirstNotif = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(notifCooldownTtlSeconds));

        if (Boolean.TRUE.equals(isFirstNotif)) {
            // Cooldown window just opened — deliver immediately
            log.info("Push Notification Sent to User {}", userId);
        } else {
            // Cooldown active — enqueue for batch delivery
            redisTemplate.opsForList().rightPush(pendingKey, message);
            log.debug("Queued pending notification for user {}: {}", userId, message);
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private String buildMessage(String botName, UUID userId) {
        return botName + " interacted with your post";
    }
}
