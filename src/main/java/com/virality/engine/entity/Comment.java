package com.virality.engine.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a comment on a Post.
 * Supports threaded replies via depth_level (max 20).
 * author_id may be a human user UUID or a bot UUID – stored as bare UUID
 * to keep the schema flexible without a polymorphic FK.
 */
@Entity
@Table(
    name = "comments",
    indexes = {
        @Index(name = "idx_comment_post", columnList = "post_id"),
        @Index(name = "idx_comment_author", columnList = "author_id"),
        @Index(name = "idx_comment_created_at", columnList = "created_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** FK to the Post this comment belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_comment_post"))
    private Post post;

    /**
     * UUID of the comment author. Can be a human user or a bot.
     * Stored as a raw UUID column (no FK constraint) for polymorphic flexibility.
     */
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** Indicates whether this comment was written by a bot. */
    @Column(name = "is_bot_comment", nullable = false)
    private boolean isBotComment;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Nesting depth (0 = top-level reply on the post).
     * Hard cap enforced at the service layer: depth_level > 20 → HTTP 422.
     */
    @Min(0)
    @Max(20)
    @Column(name = "depth_level", nullable = false)
    private int depthLevel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
