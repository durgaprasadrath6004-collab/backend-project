package com.virality.engine.service;

import com.virality.engine.dto.request.CreateCommentRequest;
import com.virality.engine.dto.request.CreatePostRequest;
import com.virality.engine.dto.request.LikePostRequest;
import com.virality.engine.dto.response.CommentResponse;
import com.virality.engine.dto.response.LikeResponse;
import com.virality.engine.dto.response.PostResponse;
import com.virality.engine.entity.Bot;
import com.virality.engine.entity.Comment;
import com.virality.engine.entity.Post;
import com.virality.engine.entity.User;
import com.virality.engine.exception.ResourceNotFoundException;
import com.virality.engine.repository.BotRepository;
import com.virality.engine.repository.CommentRepository;
import com.virality.engine.repository.PostRepository;
import com.virality.engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PostService — phases 1, 2, and 3 (business logic layer)
 *
 * ─── Transactional safety guarantee ────────────────────────────────────────
 * For bot comments the flow is:
 *   1. Run Redis guardrails (BEFORE any DB write).
 *      If any guardrail rejects, we throw immediately — nothing has been
 *      written to Postgres and no compensation is needed.
 *   2. @Transactional opens a DB transaction.
 *   3. Inside a try/catch, save the Comment entity.
 *   4. If the DB save throws, the catch block calls
 *      viralityRedisService.decrementBotCount(postId) to compensate the
 *      Lua-script INCR we did in step 1, then re-throws so Spring @Transactional
 *      rolls back the DB transaction.
 *   5. If DB save succeeds: increment virality score in Redis + trigger notification.
 *
 * This gives us best-effort Redis/DB consistency without distributed transactions.
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository         postRepository;
    private final CommentRepository      commentRepository;
    private final UserRepository         userRepository;
    private final BotRepository          botRepository;
    private final ViralityRedisService   viralityRedisService;
    private final NotificationRedisService notificationRedisService;

    // ── Phase 1 ────────────────────────────────────────────────────────────

    /**
     * Create a new post authored by a human user.
     */
    @Transactional
    public PostResponse createPost(CreatePostRequest req) {
        User author = fetchUser(req.getAuthorId());

        Post post = Post.builder()
                .author(author)
                .content(req.getContent())
                .build();

        post = postRepository.save(post);
        log.info("Post {} created by user {}", post.getId(), author.getId());

        return toPostResponse(post, 0L);
    }

    /**
     * Add a human like to a post (+20 virality).
     */
    @Transactional
    public LikeResponse likePost(UUID postId, LikePostRequest req) {
        // Verify post and user exist
        Post post = fetchPost(postId);
        fetchUser(req.getUserId());  // validates the user exists

        long newScore = viralityRedisService.incrementHumanLike(postId);
        log.info("Post {} liked by user {}. Virality → {}", postId, req.getUserId(), newScore);

        return LikeResponse.builder()
                .postId(postId)
                .userId(req.getUserId())
                .viralityScore(newScore)
                .message("Post liked successfully")
                .build();
    }

    // ── Phase 2 ────────────────────────────────────────────────────────────

    /**
     * Add a comment to a post.
     *
     * If the comment is from a bot:
     *   - Three atomic Redis guardrails are applied first.
     *   - Bot count is compensated on DB failure.
     *   - Virality scored +1 after success.
     *   - Notification engine is triggered.
     *
     * If the comment is from a human:
     *   - No guardrails.
     *   - Virality scored +50 after success.
     */
    @Transactional
    public CommentResponse addComment(UUID postId, CreateCommentRequest req) {
        Post post = fetchPost(postId);

        if (req.isBot()) {
            return addBotComment(post, req);
        } else {
            return addHumanComment(post, req);
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private CommentResponse addBotComment(Post post, CreateCommentRequest req) {
        UUID postId  = post.getId();
        UUID botId   = requireBotId(req);
        UUID humanId = requireHumanId(req);

        // Phase 2 — all three guardrails run before ANY DB write
        viralityRedisService.applyBotCommentGuardrails(postId, botId, humanId, req.getDepthLevel());

        // Validate bot exists — done after Redis guardrails to avoid DB pressure
        // on rejected requests
        Bot bot = botRepository.findById(botId)
                .orElseThrow(() -> new ResourceNotFoundException("Bot not found: " + botId));

        Comment comment;
        try {
            comment = commentRepository.save(buildComment(post, req, true));
        } catch (Exception dbEx) {
            // Phase 4 — compensate Redis INCR on DB failure
            viralityRedisService.decrementBotCount(postId);
            log.error("DB write failed for bot comment on post {}. Redis bot_count compensated. Error: {}",
                    postId, dbEx.getMessage());
            throw dbEx;  // re-throw → @Transactional rolls back DB transaction
        }

        // Virality
        long newScore = viralityRedisService.incrementBotReply(postId);

        // Phase 3 — notification engine
        notificationRedisService.handleBotInteraction(humanId, bot.getName());

        log.info("Bot {} commented on post {} (depth={}). Virality → {}",
                botId, postId, req.getDepthLevel(), newScore);

        return toCommentResponse(comment, postId, newScore);
    }

    private CommentResponse addHumanComment(Post post, CreateCommentRequest req) {
        UUID postId = post.getId();

        // Validate user exists
        fetchUser(req.getAuthorId());

        Comment comment = commentRepository.save(buildComment(post, req, false));

        long newScore = viralityRedisService.incrementHumanComment(postId);
        log.info("Human {} commented on post {}. Virality → {}", req.getAuthorId(), postId, newScore);

        return toCommentResponse(comment, postId, newScore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private Comment buildComment(Post post, CreateCommentRequest req, boolean isBot) {
        return Comment.builder()
                .post(post)
                .authorId(req.getAuthorId())
                .isBotComment(isBot)
                .content(req.getContent())
                .depthLevel(req.getDepthLevel())
                .build();
    }

    private Post fetchPost(UUID postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + postId));
    }

    private User fetchUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private UUID requireBotId(CreateCommentRequest req) {
        if (req.getBotId() == null) {
            throw new IllegalArgumentException("botId is required for bot comments");
        }
        return req.getBotId();
    }

    private UUID requireHumanId(CreateCommentRequest req) {
        if (req.getHumanId() == null) {
            throw new IllegalArgumentException("humanId is required for bot comments");
        }
        return req.getHumanId();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────────────────────────────

    private PostResponse toPostResponse(Post post, long viralityScore) {
        return PostResponse.builder()
                .id(post.getId())
                .authorId(post.getAuthor().getId())
                .authorUsername(post.getAuthor().getUsername())
                .content(post.getContent())
                .createdAt(post.getCreatedAt())
                .viralityScore(viralityScore)
                .build();
    }

    private CommentResponse toCommentResponse(Comment comment, UUID postId, long newScore) {
        return CommentResponse.builder()
                .id(comment.getId())
                .postId(postId)
                .authorId(comment.getAuthorId())
                .content(comment.getContent())
                .depthLevel(comment.getDepthLevel())
                .botComment(comment.isBotComment())
                .createdAt(comment.getCreatedAt())
                .postViralityScore(newScore)
                .build();
    }
}
