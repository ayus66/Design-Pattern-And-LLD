# Music Player with Recommendations — Low Level Design

## Problem Statement

Design a multi-user music player that can load playlists, play/pause/skip tracks, support shuffle and repeat modes, notify listeners on song changes, and produce recommendations based on swappable strategies — with each user getting a fully isolated player instance managed by a central `UserManager`.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Strategy** | `QueueTraversalStrategy` (sequential / shuffle) and `RecommendationStrategy` (genre / history / collaborative) — swappable at runtime |
| **Observer** | `PlayerObserver` — any number of listeners receive `onSongChanged` when the track changes |

---

## Class Diagram

```
UserManager
├── Map<String, User>          users           (ConcurrentHashMap)
└── Map<String, MusicPlayer>   playersByUser   (ConcurrentHashMap, one per user)

User
├── String id
└── String name

MusicPlayer
├── List<Song>                  queue
├── int                         currentIndex
├── PlaybackState               state  (PLAYING / PAUSED / STOPPED)
├── RepeatMode                  repeatMode  (NONE / REPEAT_ONE / REPEAT_ALL)
├── QueueTraversalStrategy  ──► SequentialTraversal | ShuffleTraversal
├── RecommendationStrategy  ──► GenreMatchStrategy | RecentlyPlayedStrategy | CollaborativeFilterStrategy
├── List<PlayerObserver>        observers  (CopyOnWriteArrayList)
└── Deque<Song>                 playHistory  (max 20, most-recent first)

Playlist
├── String id / ownerId / name
└── List<Song>  songs

Song
├── id, title, artist, genre, durationSec
├── equals / hashCode  (by id)
└── toString  → "title - artist"
```

---

## Core Classes

### `UserManager`

Central registry that owns one `MusicPlayer` per user. Players are created lazily on first access and reused on subsequent calls.

| Method | Description |
|---|---|
| `registerUser(user)` | Adds a `User` to the registry |
| `getUser(userId)` | Looks up a user by ID |
| `getPlayerFor(userId)` | Returns (or creates) the `MusicPlayer` bound to that user |

`computeIfAbsent` on the `ConcurrentHashMap` guarantees exactly one `MusicPlayer` is created per user even under concurrent access — no explicit synchronization needed.

---

### `User`

Simple value object: `id` + `name`. Passed to `UserManager.registerUser` to establish identity. `Playlist` stores the owner's `id` via `ownerId` to record which user a playlist belongs to.

---

### `MusicPlayer`

Controls playback. All state mutations are guarded by an internal `lock` object. Observer notifications happen outside the lock to avoid holding it during callbacks.

| Method | Description |
|---|---|
| `loadPlaylist(playlist)` | Copies playlist songs into the queue, resets index to 0 |
| `play()` | Sets state to `PLAYING`, notifies observers |
| `pause()` | Sets state to `PAUSED` |
| `next()` | Advances using `QueueTraversalStrategy`; respects `RepeatMode` |
| `previous()` | Retreats using `QueueTraversalStrategy` |
| `getRecommendations(library, n)` | Delegates to `RecommendationStrategy` |
| `addObserver(o)` | Registers a `PlayerObserver` |
| `getHistory()` | Returns the live `Deque<Song>` reference (shared with `RecentlyPlayedStrategy`) |

**Repeat mode behaviour in `next()`:**

| RepeatMode | Behaviour |
|---|---|
| `NONE` | Advance by strategy; stop at end |
| `REPEAT_ONE` | Stay on current song |
| `REPEAT_ALL` | Wrap to index 0 when strategy returns `null` |

---

### `QueueTraversalStrategy`

```java
Song next(List<Song> queue, int currentIndex);
Song previous(List<Song> queue, int currentIndex);
```

| Implementation | next | previous |
|---|---|---|
| `SequentialTraversal` | `index + 1`, or `null` at end | `index - 1`, or `null` at start |
| `ShuffleTraversal` | Random pick from queue | Same as `next` (known trade-off: no shuffle-history stack) |

---

### `RecommendationStrategy`

```java
List<Song> recommend(Song currentSong, List<Song> library, int count);
```

| Implementation | Logic |
|---|---|
| `GenreMatchStrategy` | Returns songs from the library with the same genre, excluding the current song |
| `RecentlyPlayedStrategy` | Returns the most recently played songs from `playHistory`, excluding the current song |
| `CollaborativeFilterStrategy` | Stub — returns any `count` songs excluding current (extension point for collaborative filtering) |

`RecentlyPlayedStrategy` receives the player's `Deque<Song>` **by reference** at construction, so it always sees live history without the player knowing about the strategy's internals.

---

### `PlayerObserver`

```java
void onSongChanged(Song song);
```

Registered via `addObserver`. The player uses `CopyOnWriteArrayList` so observers can be added/removed safely while iteration is in progress (e.g., during a `next()` call from another thread).

---

### Play History

- Capped at `MAX_HISTORY = 20` entries.
- Most-recently-played song is at the front (`addFirst`).
- Duplicate suppression: consecutive calls that land on the same song (e.g., `REPEAT_ONE`) do not add a duplicate entry.

---

## Demo Walkthrough (`MusicPlayerWithRecommendationDemo.main`)

**Library:** Song A (Pop), Song B (Rock), Song C (Pop)  
**Users:** Alice (u1) — full library playlist; Bob (u2) — Song B + Song C only

| Step | Action | Output |
|---|---|---|
| 1 | Register Alice & Bob; get isolated players via `UserManager` | Two separate `MusicPlayer` instances |
| 2 | Alice: `loadPlaylist` + `play()` | "[Alice] Now playing: Song A - Artist X" |
| 3 | Alice: `next()` | "[Alice] Now playing: Song B - Artist Y" |
| 4 | Bob: `loadPlaylist` + `play()` | "[Bob] Now playing: Song B - Artist Y" |
| 5 | Print both histories | Alice history: [Song B, Song A]; Bob history: [Song B] — fully isolated |
| 6 | `getPlayerFor(alice.getId())` again | Returns **same instance** — `true` |
| 7 | Alice: `setRepeatMode(REPEAT_ONE)` + `next()` | "[Alice] Now playing: Song B - Artist Y" (repeated) |
| 8 | Alice: `getRecommendations` with `GenreMatchStrategy` | `[]` — Song B is Rock, no other Rock songs |
| 9 | Alice: swap to `CollaborativeFilterStrategy` | Returns Song A and Song C (stub) |
| 10 | Alice: swap to `RecentlyPlayedStrategy` | Returns recently played songs from Alice's live history |
| 11 | Alice: `setTraversal(ShuffleTraversal)` + `next()` | Random song from Alice's queue |
| 12 | Empty player `play()` | No-op; state stays `STOPPED` |

---

## Concurrency Notes

- `UserManager` uses `ConcurrentHashMap` + `computeIfAbsent` to guarantee exactly one `MusicPlayer` per user without explicit locking.
- `MusicPlayer` uses a private `lock` object for all state mutations (`queue`, `currentIndex`, `state`, `playHistory`).
- Observer notifications are dispatched **outside** the lock so a slow observer cannot block the player.
- `CopyOnWriteArrayList` for `observers` allows safe add/remove while a notification loop is running.
- `volatile` on `state` ensures reads from other threads see the latest value without entering `synchronized`.
- Each user's `MusicPlayer` is fully isolated — no shared mutable state between Alice's and Bob's players.

---

## Open/Closed Principle

Adding a new recommendation algorithm or traversal mode requires:
1. Implement `RecommendationStrategy` or `QueueTraversalStrategy`.
2. Call `setRecommender(...)` or `setTraversal(...)`.

Zero changes to `MusicPlayer` itself — demonstrated live in the demo with `CollaborativeFilterStrategy`.

---

## Extension Points

- **New traversal** — implement `QueueTraversalStrategy` (e.g., `WeightedRandomTraversal`).
- **New recommender** — implement `RecommendationStrategy` (e.g., `ArtistMatchStrategy`, `MoodBasedStrategy`).
- **New observer** — implement `PlayerObserver` (e.g., a UI component, a scrobbler, a lyrics fetcher).
- **Persistent history** — replace `ArrayDeque` with a database-backed store; `RecentlyPlayedStrategy` is unaffected as long as the `Deque` interface is preserved.
- **Multi-user playlists** — `Playlist` already stores `ownerId`; add access control logic to `UserManager` without touching `MusicPlayer`.
- **New user store** — replace `UserManager`'s `ConcurrentHashMap` with a database-backed store to support persistent user sessions.