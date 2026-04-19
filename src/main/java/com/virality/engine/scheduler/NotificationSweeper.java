package com.virality.engine.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NotificationSweeper — Phase 3 (CRON half)
 *
 * Runs every 5 minutes.
 * Uses SCAN (not KEYS) to find all pending-notification lists,
 * aggregates them with LRANGE, logs a summarised push notification,
 * then DELetes the list.
 *
 * SCAN is used to avoid the O(N) blocking behaviour of KEYS on large keyspaces.
 * Each SCAN call returns a cursor + a batch of matching keys; we iterate until
 * the cursor wraps back to 0 (completed full scan).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

    private final StringRedisTemplate redisTemplate;

    /** Pattern for pending notification lists. */
    private static final String PENDING_NOTIFS_PATTERN = "user:*:pending_notifs";

    /**
     * Scheduled sweep — every 5 minutes.
     * cron = second minute hour day month weekday
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void sweepPendingNotifications() {
        log.info("[NotificationSweeper] Starting sweep of pending notifications...");

        List<String> pendingKeys = scanPendingNotifKeys();

        if (pendingKeys.isEmpty()) {
            log.info("[NotificationSweeper] No pending notification lists found. Sweep complete.");
            return;
        }

        log.info("[NotificationSweeper] Found {} pending notification list(s).", pendingKeys.size());

        for (String key : pendingKeys) {
            processPendingNotifList(key);
        }

        log.info("[NotificationSweeper] Sweep complete.");
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Uses SCAN with a pattern and COUNT hint to collect all matching keys.
     * Never blocks Redis unlike KEYS.
     */
    private List<String> scanPendingNotifKeys() {
        List<String> keys = new ArrayList<>();

        ScanOptions options = ScanOptions.scanOptions()
                .match(PENDING_NOTIFS_PATTERN)
                .count(100)          // hint for batch size per SCAN call
                .build();

        // execute() gives us access to the raw connection for SCAN
        redisTemplate.execute(connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands()
                    .scan(ScanOptions.scanOptions()
                            .match(PENDING_NOTIFS_PATTERN)
                            .count(100)
                            .build())) {
                while (cursor.hasNext()) {
                    byte[] keyBytes = cursor.next();
                    keys.add(new String(keyBytes));
                }
            } catch (Exception e) {
                log.error("[NotificationSweeper] Error during SCAN: {}", e.getMessage(), e);
            }
            return null;
        }, true);

        return keys;
    }

    /**
     * For a single pending_notifs key:
     *   1. LRANGE 0 -1 to get all queued messages
     *   2. Log a summarised push notification
     *   3. DEL the list
     */
    private void processPendingNotifList(String key) {
        // Extract userId from key pattern "user:{userId}:pending_notifs"
        String userId = extractUserId(key);

        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            // List may have been emptied between SCAN and LRANGE — safe to skip
            redisTemplate.delete(key);
            return;
        }

        int total = messages.size();

        // First message encodes "botName interacted with your post"
        // Extract the bot name from the first message
        String firstBotName = extractBotName(messages.get(0));

        if (total == 1) {
            log.info("Summarized Push Notification: {} interacted with your posts.", firstBotName);
        } else {
            log.info("Summarized Push Notification: {} and {} others interacted with your posts.",
                    firstBotName, total - 1);
        }

        // DEL the list atomically after we've read it
        redisTemplate.delete(key);
        log.debug("[NotificationSweeper] Deleted pending list for user {} ({} messages).", userId, total);
    }

    /** Pull userId out of "user:{userId}:pending_notifs" */
    private String extractUserId(String key) {
        // key = "user:550e8400-...:pending_notifs"
        String stripped = key.replace("user:", "").replace(":pending_notifs", "");
        return stripped;
    }

    /** Pull bot name from the notification message string. */
    private String extractBotName(String message) {
        // message = "{botName} interacted with your post"
        int idx = message.indexOf(" interacted with your post");
        return idx > 0 ? message.substring(0, idx) : message;
    }
}
