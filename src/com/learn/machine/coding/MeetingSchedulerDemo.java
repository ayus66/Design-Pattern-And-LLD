package com.learn.machine.coding;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/* ============================================================
   MEETING SCHEDULER - Machine Coding
   Core algorithm: interval overlap detection.
   Concurrency: multi-resource locking (every participant + the
   room) with a globally consistent lock-acquisition order, to
   prevent both double-booking races AND circular-wait deadlocks.

   Requirements covered in this version:
   1. Multiple rooms with capacities               - Room.capacity
   2. Book a slot for N participants, capacity-checked - bookMeeting()
   3. Notify participants about meetings/changes   - MeetingObserver
   4. Room availability + participant calendars    - meetingsByRoom/Attendee
   5. Pluggable booking strategy (FCFS / priority)  - BookingStrategy
   6. Update/reschedule + cancellation              - rescheduleMeeting()/cancelMeeting()
   ============================================================ */

// ---------- Entities ----------

class Attendee {
    private final String id;
    private final String name;

    public Attendee(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}

class Room {
    private final String id;
    private final String name;
    private final int capacity;

    public Room(String id, String name, int capacity) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getCapacity() { return capacity; }

    @Override
    public String toString() { return name; }
}

class Meeting {
    private final String id;
    private final String title;
    private final Instant start;
    private final Instant end;
    private final Attendee organizer;
    private final Set<Attendee> participants;
    private final Room room; // nullable
    private final int priority; // higher = more important, used by priority-based booking strategy

    public Meeting(String id, String title, Instant start, Instant end,
                   Attendee organizer, Set<Attendee> participants, Room room, int priority) {
        this.id = id;
        this.title = title;
        this.start = start;
        this.end = end;
        this.organizer = organizer;
        this.participants = participants;
        this.room = room;
        this.priority = priority;
    }

    // Standard interval overlap check: s1 < e2 && s2 < e1
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public Instant getStart() { return start; }
    public Instant getEnd() { return end; }
    public Attendee getOrganizer() { return organizer; }
    public Set<Attendee> getParticipants() { return participants; }
    public Room getRoom() { return room; }
    public int getPriority() { return priority; }

    @Override
    public String toString() {
        return String.format("Meeting[%s] '%s' %s-%s room=%s prio=%d", id, title, start, end,
                room == null ? "none" : room.getName(), priority);
    }
}

class MeetingConflictException extends RuntimeException {
    public MeetingConflictException(String message) { super(message); }
}

class RoomCapacityExceededException extends RuntimeException {
    public RoomCapacityExceededException(String message) { super(message); }
}

// ---------- Notification (Observer) ----------

interface MeetingObserver {
    void onMeetingBooked(Meeting meeting);
    void onMeetingCancelled(Meeting meeting);
    void onMeetingUpdated(Meeting oldMeeting, Meeting newMeeting);
}

// ---------- Booking strategy (Strategy) ----------
// Decides what happens when a new request conflicts with an existing
// meeting: reject the new request (FCFS - the default, and the ONLY
// behavior the original version had), or bump the existing meeting
// aside in favor of a higher-priority incoming one.

interface BookingStrategy {
    boolean shouldBump(int incomingPriority, Meeting existingConflict);
}

class FcfsBookingStrategy implements BookingStrategy {
    @Override
    public boolean shouldBump(int incomingPriority, Meeting existingConflict) {
        return false; // never override - first to book always wins
    }
}

class PriorityBookingStrategy implements BookingStrategy {
    @Override
    public boolean shouldBump(int incomingPriority, Meeting existingConflict) {
        return incomingPriority > existingConflict.getPriority();
    }
}

// ---------- Scheduler ----------

class MeetingScheduler {
    private final Map<String, Attendee> users = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Meeting> meetings = new ConcurrentHashMap<>();
    private final Map<String, List<Meeting>> meetingsByUser = new ConcurrentHashMap<>();
    private final Map<String, List<Meeting>> meetingsByRoom = new ConcurrentHashMap<>();
    // One lock per resource key ("user:<id>" or "room:<id>"), created
    // lazily. Locks are ALWAYS acquired in sorted-key order across every
    // booking/cancel/reschedule attempt, regardless of input order -
    // this is what prevents circular-wait deadlock between concurrent
    // requests that share overlapping attendees.
    private final Map<String, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();
    private final List<MeetingObserver> observers = new CopyOnWriteArrayList<>();
    private final BookingStrategy strategy;

    public MeetingScheduler() {
        this(new FcfsBookingStrategy());
    }

    public MeetingScheduler(BookingStrategy strategy) {
        this.strategy = strategy;
    }

    public void registerUser(Attendee user) { users.put(user.getId(), user); }
    public void registerRoom(Room room) { rooms.put(room.getId(), room); }
    public void addObserver(MeetingObserver observer) { observers.add(observer); }

    public Meeting bookMeeting(String title, Attendee organizer, Set<Attendee> participants,
                               Instant start, Instant end, Room room) {
        return bookMeeting(title, organizer, participants, start, end, room, 0);
    }

    public Meeting bookMeeting(String title, Attendee organizer, Set<Attendee> participants,
                               Instant start, Instant end, Room room, int priority) {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }

        Set<Attendee> allAttendees = new HashSet<>(participants);
        allAttendees.add(organizer);

        // Requirement 2: capacity check - cheap, no lock needed, pure
        // input validation just like the start<end check above.
        if (room != null && allAttendees.size() > room.getCapacity()) {
            throw new RoomCapacityExceededException(
                    room.getName() + " capacity is " + room.getCapacity()
                            + ", but " + allAttendees.size() + " attendees were requested");
        }

        List<String> lockKeys = buildSortedLockKeys(allAttendees, room);

        List<ReentrantLock> acquired = new ArrayList<>();
        Meeting meeting;
        List<Meeting> bumped;
        try {
            acquireAll(lockKeys, acquired);

            // Requirement 5: consult the strategy for every conflict found,
            // instead of always throwing immediately (FCFS's behavior).
            LinkedHashSet<Meeting> toBump = new LinkedHashSet<>();
            for (Attendee u : allAttendees) {
                for (Meeting existing : meetingsByUser.getOrDefault(u.getId(), Collections.emptyList())) {
                    if (existing.overlaps(start, end)) {
                        if (strategy.shouldBump(priority, existing)) {
                            toBump.add(existing);
                        } else {
                            throw new MeetingConflictException(
                                    u.getName() + " already has a conflicting meeting: " + existing.getTitle());
                        }
                    }
                }
            }
            if (room != null) {
                for (Meeting existing : meetingsByRoom.getOrDefault(room.getId(), Collections.emptyList())) {
                    if (existing.overlaps(start, end)) {
                        if (strategy.shouldBump(priority, existing)) {
                            toBump.add(existing);
                        } else {
                            throw new MeetingConflictException(
                                    "Room " + room.getName() + " already booked: " + existing.getTitle());
                        }
                    }
                }
            }

            bumped = new ArrayList<>(toBump);
            for (Meeting b : bumped) {
                removeFromIndices(b);
            }

            meeting = new Meeting(UUID.randomUUID().toString(), title, start, end,
                    organizer, participants, room, priority);
            addToIndices(meeting, allAttendees);
        } finally {
            releaseAll(acquired);
        }

        // Notifications happen OUTSIDE the lock - arbitrary observer code
        // (could be slow, could throw) must never run while holding
        // resource locks, or it would block unrelated bookings for no reason.
        for (Meeting b : bumped) {
            notifyCancelled(b);
        }
        notifyBooked(meeting);
        return meeting;
    }

    public void cancelMeeting(String meetingId) {
        Meeting meeting = meetings.get(meetingId);
        if (meeting == null) return;

        Set<Attendee> allAttendees = new HashSet<>(meeting.getParticipants());
        allAttendees.add(meeting.getOrganizer());
        List<String> lockKeys = buildSortedLockKeys(allAttendees, meeting.getRoom());

        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            acquireAll(lockKeys, acquired);
            removeFromIndices(meeting);
        } finally {
            releaseAll(acquired);
        }
        notifyCancelled(meeting);
    }

    // Requirement 6 (update): reschedule an existing meeting to a new
    // time/room, re-checking for conflicts against the NEW slot while
    // excluding the meeting being rescheduled from its own conflict check.
    public Meeting rescheduleMeeting(String meetingId, Instant newStart, Instant newEnd, Room newRoom) {
        Meeting old = meetings.get(meetingId);
        if (old == null) {
            throw new NoSuchElementException("No meeting with id " + meetingId);
        }
        if (!newStart.isBefore(newEnd)) {
            throw new IllegalArgumentException("start must be before end");
        }

        Set<Attendee> allAttendees = new HashSet<>(old.getParticipants());
        allAttendees.add(old.getOrganizer());

        if (newRoom != null && allAttendees.size() > newRoom.getCapacity()) {
            throw new RoomCapacityExceededException(
                    newRoom.getName() + " capacity is " + newRoom.getCapacity()
                            + ", but " + allAttendees.size() + " attendees were requested");
        }

        // Lock keys must cover the union of old room + new room (if
        // different) plus every attendee, so nothing else can race
        // against either the removal or the re-insertion.
        List<String> lockKeys = buildSortedLockKeys(allAttendees, old.getRoom(), newRoom);

        List<ReentrantLock> acquired = new ArrayList<>();
        Meeting updated;
        try {
            acquireAll(lockKeys, acquired);

            for (Attendee u : allAttendees) {
                for (Meeting existing : meetingsByUser.getOrDefault(u.getId(), Collections.emptyList())) {
                    if (!existing.getId().equals(meetingId) && existing.overlaps(newStart, newEnd)) {
                        throw new MeetingConflictException(
                                u.getName() + " has a conflicting meeting: " + existing.getTitle());
                    }
                }
            }
            if (newRoom != null) {
                for (Meeting existing : meetingsByRoom.getOrDefault(newRoom.getId(), Collections.emptyList())) {
                    if (!existing.getId().equals(meetingId) && existing.overlaps(newStart, newEnd)) {
                        throw new MeetingConflictException(
                                "Room " + newRoom.getName() + " already booked: " + existing.getTitle());
                    }
                }
            }

            removeFromIndices(old);
            updated = new Meeting(meetingId, old.getTitle(), newStart, newEnd,
                    old.getOrganizer(), old.getParticipants(), newRoom, old.getPriority());
            addToIndices(updated, allAttendees);
        } finally {
            releaseAll(acquired);
        }
        notifyUpdated(old, updated);
        return updated;
    }

    public List<Room> findAvailableRooms(Instant start, Instant end, int requiredCapacity) {
        List<Room> available = new ArrayList<>();
        for (Room r : rooms.values()) {
            if (r.getCapacity() < requiredCapacity) continue;
            boolean conflict = meetingsByRoom.getOrDefault(r.getId(), Collections.emptyList())
                    .stream().anyMatch(m -> m.overlaps(start, end));
            if (!conflict) available.add(r);
        }
        return available;
    }

    // ---------- Private helpers ----------

    private List<String> buildSortedLockKeys(Set<Attendee> allAttendees, Room... roomsInvolved) {
        // TreeSet gives sorted order AND automatic de-duplication in one
        // step - important for reschedule, where old room and new room
        // might be the SAME room and shouldn't produce two lock attempts
        // on the same key.
        Set<String> keySet = new TreeSet<>();
        for (Attendee u : allAttendees) keySet.add("user:" + u.getId());
        for (Room r : roomsInvolved) {
            if (r != null) keySet.add("room:" + r.getId());
        }
        return new ArrayList<>(keySet);
    }

    private void acquireAll(List<String> lockKeys, List<ReentrantLock> acquired) {
        for (String key : lockKeys) {
            ReentrantLock lock = resourceLocks.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            acquired.add(lock);
        }
    }

    private void releaseAll(List<ReentrantLock> acquired) {
        // Release order doesn't matter for correctness - only ACQUISITION
        // order needs to be consistent to prevent deadlock.
        for (ReentrantLock lock : acquired) lock.unlock();
    }

    private void addToIndices(Meeting meeting, Set<Attendee> allAttendees) {
        meetings.put(meeting.getId(), meeting);
        for (Attendee u : allAttendees) {
            meetingsByUser.computeIfAbsent(u.getId(), k -> new CopyOnWriteArrayList<>()).add(meeting);
        }
        if (meeting.getRoom() != null) {
            meetingsByRoom.computeIfAbsent(meeting.getRoom().getId(), k -> new CopyOnWriteArrayList<>()).add(meeting);
        }
    }

    private void removeFromIndices(Meeting meeting) {
        meetings.remove(meeting.getId());
        for (Attendee u : meeting.getParticipants()) {
            List<Meeting> list = meetingsByUser.get(u.getId());
            if (list != null) list.remove(meeting);
        }
        List<Meeting> orgList = meetingsByUser.get(meeting.getOrganizer().getId());
        if (orgList != null) orgList.remove(meeting);
        if (meeting.getRoom() != null) {
            List<Meeting> roomList = meetingsByRoom.get(meeting.getRoom().getId());
            if (roomList != null) roomList.remove(meeting);
        }
    }

    private void notifyBooked(Meeting meeting) {
        for (MeetingObserver o : observers) {
            try {
                o.onMeetingBooked(meeting);
            } catch (Exception e) {
                System.err.println("[MeetingScheduler] observer threw on booked: " + e);
            }
        }
    }

    private void notifyCancelled(Meeting meeting) {
        for (MeetingObserver o : observers) {
            try {
                o.onMeetingCancelled(meeting);
            } catch (Exception e) {
                System.err.println("[MeetingScheduler] observer threw on cancelled: " + e);
            }
        }
    }

    private void notifyUpdated(Meeting oldMeeting, Meeting newMeeting) {
        for (MeetingObserver o : observers) {
            try {
                o.onMeetingUpdated(oldMeeting, newMeeting);
            } catch (Exception e) {
                System.err.println("[MeetingScheduler] observer threw on updated: " + e);
            }
        }
    }
}

// ---------- Demo Harness ----------

public class MeetingSchedulerDemo {
    public static void main(String[] args) throws InterruptedException {
        MeetingScheduler scheduler = new MeetingScheduler();

        Attendee alice = new Attendee("u1", "Alice");
        Attendee bob = new Attendee("u2", "Bob");
        Attendee carol = new Attendee("u3", "Carol");
        scheduler.registerUser(alice);
        scheduler.registerUser(bob);
        scheduler.registerUser(carol);

        Room roomA = new Room("r1", "Room A", 4);
        Room roomB = new Room("r2", "Room B", 10);
        scheduler.registerRoom(roomA);
        scheduler.registerRoom(roomB);

        // Requirement 3: notification - a simple observer that just prints
        scheduler.addObserver(new MeetingObserver() {
            @Override
            public void onMeetingBooked(Meeting meeting) {
                System.out.println("  [notify] booked: " + meeting.getTitle());
            }
            @Override
            public void onMeetingCancelled(Meeting meeting) {
                System.out.println("  [notify] cancelled: " + meeting.getTitle());
            }
            @Override
            public void onMeetingUpdated(Meeting oldMeeting, Meeting newMeeting) {
                System.out.println("  [notify] rescheduled: " + oldMeeting.getTitle()
                        + " now at " + newMeeting.getStart() + "-" + newMeeting.getEnd());
            }
        });

        Instant t0 = Instant.parse("2026-07-21T10:00:00Z");
        Instant t1 = t0.plus(1, ChronoUnit.HOURS);
        Instant t2 = t0.plus(2, ChronoUnit.HOURS);

        System.out.println("-- Happy path --");
        Meeting m1 = scheduler.bookMeeting("Design Review", alice, Collections.singleton(bob), t0, t1, roomA);
        System.out.println("Booked: " + m1);

        System.out.println("\n-- Participant conflict (Bob already busy) --");
        try {
            scheduler.bookMeeting("1:1", bob, Collections.singleton(carol), t0, t1, roomB);
            System.out.println("UNEXPECTED: no conflict detected");
        } catch (MeetingConflictException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        System.out.println("\n-- Room conflict (Room A already booked, different people) --");
        try {
            scheduler.bookMeeting("Standup", carol, Collections.emptySet(), t0, t1, roomA);
            System.out.println("UNEXPECTED: no conflict detected");
        } catch (MeetingConflictException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        System.out.println("\n-- Non-conflicting booking succeeds (different time, same room) --");
        Meeting m2 = scheduler.bookMeeting("Follow-up", alice, Collections.singleton(bob), t1, t2, roomA);
        System.out.println("Booked: " + m2);

        System.out.println("\n-- Available rooms for [t0,t1) with capacity>=2 --");
        System.out.println(scheduler.findAvailableRooms(t0, t1, 2));
        System.out.println("(Room A is taken, only Room B should show)");

        System.out.println("\n-- Requirement 2: capacity check --");
        try {
            scheduler.bookMeeting("Big meeting", alice,
                    new HashSet<>(Arrays.asList(bob, carol, new Attendee("u4", "Dave"), new Attendee("u5", "Eve"))),
                    t2, t2.plus(1, ChronoUnit.HOURS), roomA); // 5 attendees, Room A capacity is 4
            System.out.println("UNEXPECTED: capacity was not enforced");
        } catch (RoomCapacityExceededException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        System.out.println("\n-- Requirement 6: reschedule proof --");
        Meeting m2Rescheduled = scheduler.rescheduleMeeting(m2.getId(), t2, t2.plus(1, ChronoUnit.HOURS), roomB);
        System.out.println("Rescheduled: " + m2Rescheduled);
        System.out.println("Old slot [t1,t2) in Room A should be free again:");
        System.out.println(scheduler.findAvailableRooms(t1, t2, 1));

        System.out.println("\n-- Reschedule into a conflicting slot should still be rejected --");
        try {
            scheduler.rescheduleMeeting(m1.getId(), t2, t2.plus(1, ChronoUnit.HOURS), roomB);
            System.out.println("UNEXPECTED: no conflict detected on reschedule");
        } catch (MeetingConflictException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }

        System.out.println("\n-- Cancel + rebook proof --");
        scheduler.cancelMeeting(m1.getId());
        Meeting m3 = scheduler.bookMeeting("Replacement meeting", bob, Collections.singleton(carol), t0, t1, roomA);
        System.out.println("Rebooked into freed slot: " + m3);

        System.out.println("\n-- Requirement 5: priority-based booking strategy (separate scheduler) --");
        MeetingScheduler priorityScheduler = new MeetingScheduler(new PriorityBookingStrategy());
        priorityScheduler.registerUser(alice);
        priorityScheduler.registerUser(bob);
        priorityScheduler.registerRoom(roomA);
        Instant p0 = Instant.parse("2026-07-22T09:00:00Z");
        Instant p1 = p0.plus(1, ChronoUnit.HOURS);
        Meeting lowPriority = priorityScheduler.bookMeeting("Routine sync", alice, Collections.singleton(bob), p0, p1, roomA, 1);
        System.out.println("Booked low-priority meeting: " + lowPriority);
        Meeting highPriority = priorityScheduler.bookMeeting("URGENT incident review", alice, Collections.singleton(bob), p0, p1, roomA, 10);
        System.out.println("Higher-priority meeting BUMPED the existing one: " + highPriority);
        System.out.println("Rooms available for that slot now (Room A correctly STILL shows as taken - "
                + "the bumped-in meeting occupies it, bumping just replaced who's using it): "
                + priorityScheduler.findAvailableRooms(p0, p1, 1));

        System.out.println("\n-- Concurrency proof: 20 threads racing to book the SAME overlapping slot for the SAME people --");
        Instant raceStart = t2.plus(2, ChronoUnit.HOURS);
        Instant raceEnd = raceStart.plus(1, ChronoUnit.HOURS);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        int threadCount = 20;
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    scheduler.bookMeeting("Race meeting", alice, new HashSet<>(Arrays.asList(bob, carol)), raceStart, raceEnd, roomB);
                    successCount.incrementAndGet();
                } catch (MeetingConflictException e) {
                    conflictCount.incrementAndGet();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("Successful bookings (expect exactly 1): " + successCount.get());
        System.out.println("Rejected as conflicts (expect exactly 19): " + conflictCount.get());

        System.out.println("\n-- Deadlock-avoidance proof: reversed attendee order, high contention, must never hang --");
        Instant deadlockStart = raceEnd.plus(1, ChronoUnit.HOURS);
        Instant deadlockEnd = deadlockStart.plus(1, ChronoUnit.HOURS);
        Thread t1Thread = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    scheduler.bookMeeting("A-" + i, alice, Collections.singleton(bob), deadlockStart, deadlockEnd, null);
                } catch (MeetingConflictException ignored) { }
            }
        });
        Thread t2Thread = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    scheduler.bookMeeting("B-" + i, bob, Collections.singleton(alice), deadlockStart, deadlockEnd, null);
                } catch (MeetingConflictException ignored) { }
            }
        });
        t1Thread.start();
        t2Thread.start();
        t1Thread.join(5000);
        t2Thread.join(5000);
        boolean hung = t1Thread.isAlive() || t2Thread.isAlive();
        System.out.println(hung ? "DEADLOCK DETECTED - threads did not finish" : "No deadlock - both threads completed cleanly");
    }
}