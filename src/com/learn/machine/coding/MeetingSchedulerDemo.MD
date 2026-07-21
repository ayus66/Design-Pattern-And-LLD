# Meeting Scheduler — Low Level Design

## Problem Statement

Design a meeting scheduler that supports multiple rooms with capacities, books meetings for N participants with conflict detection, notifies attendees of changes, allows reschedule and cancellation, and is correct under concurrent booking requests without deadlocks.

---

## Design Patterns Used

| Pattern | Where |
|---|---|
| **Strategy** | `BookingStrategy` — `FcfsBookingStrategy` and `PriorityBookingStrategy` are plugged in at construction time |
| **Observer** | `MeetingObserver` — listeners are notified after every book, cancel, or reschedule (outside the lock) |

---

## Class Diagram

```
MeetingScheduler
├── Map<String, Attendee>        users
├── Map<String, Room>            rooms
├── Map<String, Meeting>         meetings
├── Map<String, List<Meeting>>   meetingsByUser
├── Map<String, List<Meeting>>   meetingsByRoom
├── Map<String, ReentrantLock>   resourceLocks  (per user + room)
├── List<MeetingObserver>        observers  (CopyOnWriteArrayList)
└── BookingStrategy              strategy

Meeting
└── id, title, start (Instant), end (Instant), organizer, participants, room, priority

Room
└── id, name, capacity

Attendee
└── id, name

BookingStrategy  (Strategy interface)
├── FcfsBookingStrategy      — first-come-first-served, never bumps
└── PriorityBookingStrategy  — higher priority incoming meeting bumps existing

MeetingObserver  (Observer interface)
└── onMeetingBooked / onMeetingCancelled / onMeetingUpdated
```

---

## Core Classes

### `MeetingScheduler`

The central service. Manages user/room registries, meeting indices, and the locking infrastructure.

```java
MeetingScheduler scheduler = new MeetingScheduler();          // FCFS
MeetingScheduler priority  = new MeetingScheduler(new PriorityBookingStrategy());
```

| Method | Behaviour |
|---|---|
| `bookMeeting(...)` | Validates capacity, acquires all resource locks in sorted order, checks conflicts, optionally bumps, adds to indices, notifies observers |
| `cancelMeeting(id)` | Acquires resource locks, removes from all indices, notifies observers |
| `rescheduleMeeting(id, newStart, newEnd, newRoom)` | Locks union of old + new room plus all attendees, checks new slot for conflicts (excluding self), replaces in indices |
| `findAvailableRooms(start, end, capacity)` | Lock-free read; checks each room's meeting list for interval overlap |

---

### Interval Overlap Check

```java
// s1 < e2 && s2 < e1  — standard open-interval overlap
public boolean overlaps(Instant otherStart, Instant otherEnd) {
    return start.isBefore(otherEnd) && otherStart.isBefore(end);
}
```

Two meetings overlap if and only if neither ends before the other starts.

---

### Deadlock Avoidance — Sorted Lock Acquisition

The scheduler builds a canonical sorted key set for every booking/cancel/reschedule before acquiring any lock. All threads acquire locks in the same global order regardless of input order, eliminating circular-wait — the necessary condition for deadlock.

```
"room:r1" < "room:r2" < "user:u1" < "user:u2"   (TreeSet, lexicographic)
```

Reschedule also includes both the **old** room and the **new** room in the key set (de-duplicated by `TreeSet`) so nothing can race against either the removal or the re-insertion.

---

### `BookingStrategy`

| Strategy | Behaviour |
|---|---|
| `FcfsBookingStrategy` | `shouldBump` always returns `false` — first booking always wins, new conflicting request throws `MeetingConflictException` |
| `PriorityBookingStrategy` | Returns `true` when `incomingPriority > existingConflict.getPriority()` — the existing meeting is cancelled and the higher-priority one takes its slot |

---

### Observer Notifications

Observers are called **outside** the resource locks, after all index changes are committed. This prevents slow observer code (network calls, DB writes) from blocking unrelated concurrent bookings.

```java
scheduler.addObserver(new MeetingObserver() {
    public void onMeetingBooked(Meeting m)  { ... }
    public void onMeetingCancelled(Meeting m) { ... }
    public void onMeetingUpdated(Meeting old, Meeting next) { ... }
});
```

---

## Demo Walkthrough (`MeetingSchedulerDemo.main`)

| Step | Scenario | Result |
|---|---|---|
| 1 | Alice books "Design Review" [t0, t1) in Room A with Bob | BOOKED |
| 2 | Bob books "1:1" in same slot [t0, t1) | REJECTED — participant conflict |
| 3 | Carol books "Standup" in Room A [t0, t1) | REJECTED — room conflict |
| 4 | Alice books "Follow-up" [t1, t2) — different time, same room | BOOKED |
| 5 | `findAvailableRooms([t0,t1), capacity≥2)` | Only Room B (Room A taken) |
| 6 | 5 attendees booked into Room A (capacity 4) | REJECTED — capacity exceeded |
| 7 | Reschedule "Follow-up" to [t2, t3) in Room B | Succeeds; [t1,t2) in Room A freed |
| 8 | Reschedule m1 into [t2,t3) — Alice+Bob already there | REJECTED — conflict |
| 9 | Cancel m1; rebook same slot | Freed slot accepted |
| 10 | Priority scheduler: low-priority bumped by high-priority | High-priority wins, low-priority cancelled |
| 11 | 20 threads race to book same slot | Exactly 1 succeeds, 19 conflict |
| 12 | 2 threads 200× booking reversed attendee order | No deadlock — both complete within 5 s |

---

## Concurrency Notes

- **Per-resource `ReentrantLock`** — one lock per user and room key, created lazily via `computeIfAbsent`. Unrelated bookings never contend.
- **Sorted acquisition** — `TreeSet` over all lock keys; every thread acquires in the same global order. Proven deadlock-free (eliminates circular-wait).
- **`CopyOnWriteArrayList`** for `observers` and per-attendee meeting lists — safe concurrent reads during iteration.
- Notifications always run **after** `releaseAll` — arbitrary observer code can never hold resource locks.

---

## Extension Points

- **New booking strategy** — implement `BookingStrategy` (e.g., `RoundRobinStrategy`, `SeniorityStrategy`) without touching `MeetingScheduler`.
- **Recurring meetings** — add a `RecurrenceRule` field to `Meeting`; `bookMeeting` expands it into individual instances before conflict-checking.
- **Persistence** — replace in-memory `ConcurrentHashMap` indices with a DB-backed store; the locking model transfers to row-level DB locks.
- **Calendar view** — add `getMeetingsForUser(attendeeId, date)` using the existing `meetingsByUser` index.
- **New observer** — implement `MeetingObserver` for email/calendar sync/Slack notifications without modifying core booking logic.