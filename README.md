# Social Virality Engine

A production-ready Spring Boot 3.x microservice that combines PostgreSQL persistence with a Redis-based virality scoring engine, atomic guardrails, and a smart notification batching system.

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Quick Start](#quick-start)
3. [API Reference](#api-reference)
4. [Phase-by-Phase Design](#phase-by-phase-design)
5. [Thread-Safety Guarantee for Atomic Locks (Phase 2)](#thread-safety-guarantee-for-atomic-locks-phase-2)
6. [Notification Engine (Phase 3)](#notification-engine-phase-3)
7. [Stateless Guarantee (Phase 4)](#stateless-guarantee-phase-4)
8. [Configuration Reference](#configuration-reference)

---

## Architecture Overview

```
HTTP Clients
     │
     ▼
PostController  ──►  PostService  ──►  ViralityRedisService  ──►  Redis 7
UserBotController       │
                        ▼
                 CommentRepository
                 PostRepository       ──►  PostgreSQL 15
                 UserRepository
                 BotRepository

NotificationSweeper (@Scheduled) ──►  Redis SCAN  ──►  Logger
```

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- JDK 17+
- Maven 3.9+

### 1. Start infrastructure
```bash
cd social-virality-engine
docker compose up -d
```

Wait for both services to be healthy:
```bash
docker compose ps
```

### 2. Build & run the application
```bash
mvn clean package -DskipTests
java -jar target/social-virality-engine-1.0.0.jar
```

Or with Maven dev mode:
```bash
mvn spring-boot:run
```

### 3. Import Postman collection
Import `SocialViralityEngine.postman_collection.json` into Postman.

Run requests in order:
1. **Create User** → `userId` variable auto-set
2. **Create Bot**  → `botId` variable auto-set
3. **Create Post** → `postId` variable auto-set
4. **Like Post** → virality +20
5. **Add Bot Comment** → all three guardrails applied
6. **Add Human Comment** → virality +50

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/users` | Create a human user |
| `GET`  | `/api/users` | List all users |
| `GET`  | `/api/users/{userId}` | Get user by ID |
| `POST` | `/api/bots` | Create a bot |
| `GET`  | `/api/bots` | List all bots |
| `GET`  | `/api/bots/{botId}` | Get bot by ID |
| `POST` | `/api/posts` | Create a post |
| `POST` | `/api/posts/{postId}/comments` | Add comment (with guardrails for bots) |
| `POST` | `/api/posts/{postId}/like` | Like a post |
| `GET`  | `/api/posts/{postId}/virality` | Get current virality score |

### Error Codes

| HTTP Status | Scenario |
|-------------|----------|
| `201 Created` | Resource successfully created |
| `400 Bad Request` | Bean validation failure |
| `404 Not Found` | User / Bot / Post not found in DB |
| `422 Unprocessable Entity` | `depthLevel > 20` (vertical cap) |
| `429 Too Many Requests` | Horizontal bot cap (>100) or cooldown active |

All error responses follow **RFC 7807 Problem Detail** format:
```json
{
  "type": "urn:virality:error:rate-limited",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Cooldown active for bot X on human Y. Retry after 600 seconds.",
  "timestamp": "2024-01-15T10:30:00Z",
  "path": "/api/posts/abc/comments"
}
```

---

## Phase-by-Phase Design

### Phase 1 — JPA Entities

Four entities with proper FK relationships:

| Entity | PK | Notable constraints |
|--------|----|---------------------|
| `User` | UUID | `username UNIQUE NOT NULL` |
| `Bot` | UUID | — |
| `Post` | UUID | `author_id FK → users.id` |
| `Comment` | UUID | `post_id FK → posts.id`, `depth_level CHECK 0..20` |

`Comment.authorId` is stored as a raw UUID column (no FK) to support both human and bot authors without a polymorphic FK. The `is_bot_comment` flag distinguishes the two.

---

## Thread-Safety Guarantee for Atomic Locks (Phase 2)

This is the most critical part of the design. Three separate guardrails protect bot comments:

---

### Guardrail 1 — Horizontal Bot Count Cap (`post:{postId}:bot_count ≤ 100`)

**Problem**: A naïve approach of `INCR` + `GET` + conditional `DECR` has a **TOCTOU (Time-of-Check-Time-of-Use) race window**. Between the INCR (count goes to 100) and the check, another thread can also INCR (count goes to 101), and both threads see 100 at check time, meaning *two requests can breach the cap simultaneously*.

**Solution — Lua Script executed on the Redis server**:

```lua
local current = redis.call('INCR', KEYS[1])
if current > tonumber(ARGV[1]) then
  redis.call('DECR', KEYS[1])
  return -1
end
return current
```

**Why this is race-condition safe**:
- Redis executes Lua scripts **atomically on the single-threaded event loop**.
- No other Redis command can be processed between the `INCR` and the `if` check — they run as an uninterruptible unit.
- When 200 concurrent requests fire simultaneously, each gets its own sequential slot in the Lua execution queue. The 101st caller receives `current = 101`, triggers the `DECR` rollback atomically, and returns `-1` — the service immediately throws HTTP 429.
- **The counter can never reach 101 under any load.**

Implementation: [`ViralityRedisService.java`](src/main/java/com/virality/engine/service/ViralityRedisService.java)

```java
private static final DefaultRedisScript<Long> INCR_WITH_CAP_SCRIPT = ...;

// Executed via Spring's RedisTemplate.execute():
Long result = redisTemplate.execute(INCR_WITH_CAP_SCRIPT, List.of(key), String.valueOf(cap));
if (result == -1L) throw new RateLimitException("horizontal cap reached");
```

---

### Guardrail 2 — Vertical Depth Cap (`depthLevel ≤ 20`)

**Why no race condition**: This is a pure arithmetic check on the request field — no shared state is read or modified. If `depthLevel > 20`, the request is rejected before any I/O begins.

**Error response**: HTTP 422 Unprocessable Entity.

---

### Guardrail 3 — Per-Bot-Per-Human Cooldown (`cooldown:bot_{botId}:human_{humanId}`, TTL=600s)

**Problem**: Two concurrent bot-comment requests for the same `(botId, humanId)` pair must not both succeed.

**Solution — Redis `SET NX EX` (single atomic command)**:

```java
Boolean set = redisTemplate.opsForValue()
    .setIfAbsent(key, "1", Duration.ofSeconds(600));
```

Translates to:
```
SET cooldown:bot_X:human_Y 1 NX EX 600
```

**Why this is race-condition safe**:
- `SET NX EX` is a **single atomic command** in Redis. It cannot be split into two operations.
- Among any number of concurrent callers with the same `(botId, humanId)`, exactly **one** receives `true` (key did not exist, now set). All others receive `false` (key already exists).
- The rejected callers immediately get HTTP 429 — no DB write, no virality credit.
- After 600 seconds the key expires automatically; the bot can interact with that human again.

---

### Order of Guardrail Execution

```
Request arrives
      │
      ▼
[1] Depth check (arithmetic, no I/O)
      │ fail → HTTP 422
      ▼
[2] Lua INCR + cap check (atomic Redis)
      │ fail → HTTP 429 (DECR already done inside Lua)
      ▼
[3] SET NX EX cooldown check (atomic Redis)
      │ fail → HTTP 429
      ▼
DB write (PostgreSQL)
      │ fail → DECR bot_count (compensation)
      ▼
INCRBY virality_score
      ▼
Notification engine
```

**All three checks happen before any DB write**, so a rejected request has zero Postgres impact.

---

## Notification Engine (Phase 3)

### Throttler (per bot interaction)

```
Bot comments on User's post
          │
          ▼
  SET NX EX notif_cooldown:{userId} (TTL=900s)
          │
    ┌─────┴─────┐
    │ Won slot  │ Already set
    ▼           ▼
 Log push    RPUSH user:{userId}:pending_notifs
 notification  (message queued for batch)
```

### CRON Sweeper (every 5 minutes)

```java
@Scheduled(cron = "0 */5 * * * *")
public void sweepPendingNotifications()
```

1. **SCAN** `user:*:pending_notifs` with `COUNT 100` hints — never blocks Redis.
2. For each key: **LRANGE 0 -1** → collect all messages.
3. Log: `"Summarized Push Notification: Aria and 3 others interacted with your posts."`
4. **DEL** the list.

> **Why SCAN instead of KEYS?** `KEYS *` is O(N) and blocks the Redis event loop for the duration of the scan — this can cause latency spikes or timeouts for other clients. `SCAN` is O(1) per call and iterates in small cursor-based batches, making it safe for production use.

---

## Stateless Guarantee (Phase 4)

| Requirement | How it's met |
|-------------|--------------|
| No `HashMap` / static counters | Zero in-memory state; all counters live in Redis |
| All guardrails in Redis | Every check uses `RedisTemplate` before touching Postgres |
| Compensation on DB failure | `catch` block calls `decrementBotCount()` → Redis DECR |
| Transactional boundary | `@Transactional` on `PostService` + Redis compensation in `catch` |
| Multiple app instances | Any instance can serve any request; Redis is the single source of truth |

### Compensation Flow (DB Failure)

```java
try {
    comment = commentRepository.save(buildComment(...));  // Postgres write
} catch (Exception dbEx) {
    viralityRedisService.decrementBotCount(postId);       // Redis DECR
    throw dbEx;                                            // triggers @Transactional rollback
}
```

The Lua script incremented `bot_count` to N. If Postgres then fails, we DECR back to N-1. The virality score is only incremented *after* a successful DB write — so no compensation is needed there.

---

## Configuration Reference

All tunable values are in `application.yml` under the `virality.notification.*` namespace:

| Property | Default | Description |
|----------|---------|-------------|
| `bot-reply-score` | `1` | Virality points per bot comment |
| `human-like-score` | `20` | Virality points per human like |
| `human-comment-score` | `50` | Virality points per human comment |
| `bot-horizontal-cap` | `100` | Max bot comments per post |
| `depth-vertical-cap` | `20` | Max comment nesting depth |
| `bot-cooldown-ttl-seconds` | `600` | Bot-per-human cooldown window (10 min) |
| `notif-cooldown-ttl-seconds` | `900` | Notification throttle window (15 min) |

Override on the command line:
```bash
java -jar target/*.jar \
  --virality.notification.bot-horizontal-cap=50 \
  --virality.notification.bot-cooldown-ttl-seconds=300
```

---

## Redis Key Reference

| Key Pattern | Type | Purpose |
|-------------|------|---------|
| `post:{postId}:virality_score` | STRING (counter) | Cumulative virality score |
| `post:{postId}:bot_count` | STRING (counter) | Number of bot comments (capped at 100) |
| `cooldown:bot_{botId}:human_{humanId}` | STRING (flag, TTL) | Per-bot-per-human cooldown |
| `notif_cooldown:{userId}` | STRING (flag, TTL) | Per-user notification cooldown |
| `user:{userId}:pending_notifs` | LIST | Queued notification messages |
