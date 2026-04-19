package com.virality.engine.controller;

import com.virality.engine.entity.Bot;
import com.virality.engine.entity.User;
import com.virality.engine.service.UserBotService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * UserBotController — management endpoints for Users and Bots.
 *
 * These are needed to seed data before testing the post/comment/like flow.
 * In a production system these would live behind authentication.
 */
@RestController
@RequiredArgsConstructor
public class UserBotController {

    private final UserBotService userBotService;

    // ── Users ─────────────────────────────────────────────────────────────

    @PostMapping("/api/users")
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest req) {
        User user = userBotService.createUser(req.getUsername(), req.isPremium());
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @GetMapping("/api/users")
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userBotService.getAllUsers());
    }

    @GetMapping("/api/users/{userId}")
    public ResponseEntity<User> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(userBotService.getUser(userId));
    }

    // ── Bots ──────────────────────────────────────────────────────────────

    @PostMapping("/api/bots")
    public ResponseEntity<Bot> createBot(@RequestBody CreateBotRequest req) {
        Bot bot = userBotService.createBot(req.getName(), req.getPersonaDescription());
        return ResponseEntity.status(HttpStatus.CREATED).body(bot);
    }

    @GetMapping("/api/bots")
    public ResponseEntity<List<Bot>> getAllBots() {
        return ResponseEntity.ok(userBotService.getAllBots());
    }

    @GetMapping("/api/bots/{botId}")
    public ResponseEntity<Bot> getBot(@PathVariable UUID botId) {
        return ResponseEntity.ok(userBotService.getBot(botId));
    }

    // ── Inner request DTOs (lightweight, only used in this controller) ────

    @Data
    public static class CreateUserRequest {
        private String username;
        private boolean premium;
    }

    @Data
    public static class CreateBotRequest {
        private String name;
        private String personaDescription;
    }
}
