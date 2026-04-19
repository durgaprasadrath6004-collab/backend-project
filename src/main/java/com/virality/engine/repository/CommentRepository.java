package com.virality.engine.repository;

import com.virality.engine.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** All comments for a given post ordered by creation time. */
    List<Comment> findByPostIdOrderByCreatedAtAsc(UUID postId);

    /** Count bot comments on a specific post (used for debug/verification). */
    long countByPostIdAndIsBotComment(UUID postId, boolean isBotComment);
}
