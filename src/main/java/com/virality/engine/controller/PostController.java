package com.virality.engine.controller;

import com.virality.engine.dto.request.CreateCommentRequest;
import com.virality.engine.dto.request.CreatePostRequest;
import com.virality.engine.dto.request.LikePostRequest;
import com.virality.engine.dto.response.CommentResponse;
import com.virality.engine.dto.response.LikeResponse;
import com.virality.engine.dto.response.PostResponse;
import com.virality.engine.service.PostService;
import com.virality.engine.service.ViralityRedisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * PostController — exposes the three required REST endpoints.
 *
 * POST /api/posts                    → create a post
 * POST /api/posts/{postId}/comments  → add a comment (with guardrails)
 * POST /api/posts/{postId}/like      → like a post
 *
 * Extra read endpoints are provided for convenience / Postman testing.
 */
@Slf4j
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService          postService;
    private final ViralityRedisService viralityRedisService;

    // ── POST /api/posts ───────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest req) {
        PostResponse resp = postService.createPost(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    // ── POST /api/posts/{postId}/comments ─────────────────────────────────

    @PostMapping("/{postId}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest req) {
        CommentResponse resp = postService.addComment(postId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    // ── POST /api/posts/{postId}/like ─────────────────────────────────────

    @PostMapping("/{postId}/like")
    public ResponseEntity<LikeResponse> likePost(
            @PathVariable UUID postId,
            @Valid @RequestBody LikePostRequest req) {
        LikeResponse resp = postService.likePost(postId, req);
        return ResponseEntity.ok(resp);
    }

    // ── GET /api/posts/{postId}/virality ─────────────────────────────────
    // Convenience endpoint — shows current virality score without modifying it.

    @GetMapping("/{postId}/virality")
    public ResponseEntity<Long> getViralityScore(@PathVariable UUID postId) {
        long score = viralityRedisService.getViralityScore(postId);
        return ResponseEntity.ok(score);
    }
}
