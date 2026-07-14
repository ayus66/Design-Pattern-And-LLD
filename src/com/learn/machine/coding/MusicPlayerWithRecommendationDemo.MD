# Music Player with Recommendations — Low Level Design

## Problem Statement

Design a music player that can load playlists, play/pause/skip tracks, support shuffle and repeat modes, notify listeners on song changes, and produce recommendations based on swappable strategies — all without modifying core player logic when extending behaviour.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Strategy** | `QueueTraversalStrategy` (sequential / shuffle) and `RecommendationStrategy` (genre / history / collaborative) — swappable at runtime |
| **Observer** | `PlayerObserver` — any number of listeners receive `onSongChanged` when the track changes |

---

## Class Diagram

```
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
├── String id / name
└── List<Song>  songs

Song
├── id, title, artist, genre, durationSec
├── equals / hashCode  (by id)
└── toString  → "title - artist"
```

---

## Core Classes

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

| Step | Action | Output / State |
|---|---|---|
| 1 | `loadPlaylist` + `play()` | "Now playing: Song A - Artist X" |
| 2 | `next()` | "Now playing: Song B - Artist Y" |
| 3 | `setRepeatMode(REPEAT_ONE)` + `next()` | "Now playing: Song B - Artist Y" (repeated) |
| 4 | `getRecommendations` with `GenreMatchStrategy` | `[]` — Song B is Rock, no other Rock songs |
| 5 | Swap to `CollaborativeFilterStrategy` | Returns Song A and Song C (stub) |
| 6 | Swap to `RecentlyPlayedStrategy` | Returns recently played songs from live history |
| 7 | `setTraversal(ShuffleTraversal)` + `next()` | Random song from queue |
| 8 | Empty player `play()` | No-op; state stays `STOPPED` |

---

## Concurrency Notes

- `MusicPlayer` uses a private `lock` object for all state mutations (`queue`, `currentIndex`, `state`, `playHistory`).
- Observer notifications are dispatched **outside** the lock so a slow observer cannot block the player.
- `CopyOnWriteArrayList` for `observers` allows safe add/remove while a notification loop is running.
- `volatile` on `state` ensures reads from other threads see the latest value without entering `synchronized`.

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