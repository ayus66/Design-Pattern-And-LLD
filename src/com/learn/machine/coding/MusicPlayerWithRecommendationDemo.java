package com.learn.machine.coding;


import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;


class Song {
    private final String id;
    private final String title;
    private final String artist;
    private final String genre;
    private final int durationSec;

    public Song(String id, String title, String artist, String genre, int durationSec) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.genre = genre;
        this.durationSec = durationSec;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getGenre() { return genre; }
    public int getDurationSec() { return durationSec; }

    @Override
    public String toString() { return title + " - " + artist; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Song)) return false;
        return id.equals(((Song) o).id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}


class Playlist {
    private final String id;
    private final String ownerId;
    private final String name;
    private final List<Song> songs = new ArrayList<>();

    public Playlist(String id, String ownerId, String name) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
    }

    public void addSong(Song song) { songs.add(song); }
    public void removeSong(String songId) { songs.removeIf(s -> s.getId().equals(songId)); }
    public List<Song> getSongs() { return Collections.unmodifiableList(songs); }
    public String getName() { return name; }
    public String getOwnerId() { return ownerId; }
}

class User {
    private final String id;
    private final String name;

    public User(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }
}

enum PlaybackState {
    PLAYING, PAUSED, STOPPED
}

enum RepeatMode {
    NONE, REPEAT_ONE, REPEAT_ALL
}

interface PlayerObserver {
    void onSongChanged(Song song);
}

interface QueueTraversalStrategy {
    Song next(List<Song> queue, int currentIndex);
    Song previous(List<Song> queue, int currentIndex);
}

class SequentialTraversal implements QueueTraversalStrategy {
    @Override
    public Song next(List<Song> queue, int idx) {
        if (idx + 1 < queue.size()) return queue.get(idx + 1);
        return null; // end of queue
    }

    @Override
    public Song previous(List<Song> queue, int idx) {
        return idx - 1 >= 0 ? queue.get(idx - 1) : null;
    }
}

class ShuffleTraversal implements QueueTraversalStrategy {
    private final Random rnd = new Random();

    @Override
    public Song next(List<Song> queue, int idx) {
        if (queue.isEmpty()) return null;
        return queue.get(rnd.nextInt(queue.size()));
    }

    @Override
    public Song previous(List<Song> queue, int idx) {
        // Simplification: a production version would maintain a shuffle-history
        // stack instead of re-randomizing. Flagged here as a known trade-off.
        return next(queue, idx);
    }
}

interface RecommendationStrategy {
    List<Song> recommend(Song currentSong, List<Song> library, int count);
}

class RecentlyPlayedStrategy implements RecommendationStrategy {
    private final Deque<Song> history;

    public RecentlyPlayedStrategy(Deque<Song> history) {
        this.history = history;
    }

    @Override
    public List<Song> recommend(Song currentSong, List<Song> library, int count) {
        return history.stream()
                .filter(s -> currentSong == null || !s.getId().equals(currentSong.getId()))
                .limit(count)
                .collect(Collectors.toList());
    }
}

class GenreMatchStrategy implements RecommendationStrategy {
    @Override
    public List<Song> recommend(Song currentSong, List<Song> library, int count) {
        if (currentSong == null) return Collections.emptyList();
        return library.stream()
                .filter(s -> s.getGenre().equals(currentSong.getGenre()))
                .filter(s -> !s.getId().equals(currentSong.getId()))
                .limit(count)
                .collect(Collectors.toList());
    }
}

// Extension point demo: added live to prove Open/Closed Principle holds
class CollaborativeFilterStrategy implements RecommendationStrategy {
    // In production this would look at other users' listening patterns.
    // Stubbed here to demonstrate the extension point costs zero changes
    // to MusicPlayer.
    @Override
    public List<Song> recommend(Song currentSong, List<Song> library, int count) {
        return library.stream()
                .filter(s -> currentSong == null || !s.getId().equals(currentSong.getId()))
                .limit(count)
                .collect(Collectors.toList());
    }
}

class MusicPlayer {
    private static final int MAX_HISTORY = 20;

    private final List<Song> queue = new ArrayList<>();
    private int currentIndex = -1;
    private volatile PlaybackState state = PlaybackState.STOPPED;
    private RepeatMode repeatMode = RepeatMode.NONE;
    private QueueTraversalStrategy traversal = new SequentialTraversal();
    private RecommendationStrategy recommender = new GenreMatchStrategy();
    private final List<PlayerObserver> observers = new CopyOnWriteArrayList<>();
    // Most-recently-played first. Shared by reference with RecentlyPlayedStrategy
    // so the strategy always sees live data without the player knowing about it.
    private final Deque<Song> playHistory = new ArrayDeque<>();
    private final Object lock = new Object();

    // Exposes the same deque instance so RecentlyPlayedStrategy can be
    // constructed with it: new RecentlyPlayedStrategy(player.getHistory())
    public Deque<Song> getHistory() {
        return playHistory;
    }

    public void loadQueue(List<Song> songs) {
        synchronized (lock) {
            queue.clear();
            queue.addAll(songs);
            currentIndex = songs.isEmpty() ? -1 : 0;
        }
    }

    public void loadPlaylist(Playlist playlist) {
        loadQueue(playlist.getSongs());
    }

    public void play() {
        synchronized (lock) {
            if (currentIndex < 0 || currentIndex >= queue.size()) return;
            state = PlaybackState.PLAYING;
        }
        notifyObservers(getCurrentSong());
    }

    public void pause() {
        synchronized (lock) {
            state = PlaybackState.PAUSED;
        }
    }

    public void next() {
        Song nextSong;
        synchronized (lock) {
            if (queue.isEmpty()) {
                state = PlaybackState.STOPPED;
                return;
            }
            if (repeatMode == RepeatMode.REPEAT_ONE) {
                nextSong = getCurrentSongLocked();
            } else {
                nextSong = traversal.next(queue, currentIndex);
                if (nextSong == null && repeatMode == RepeatMode.REPEAT_ALL) {
                    currentIndex = 0;
                    nextSong = queue.get(0);
                } else if (nextSong != null) {
                    currentIndex = queue.indexOf(nextSong);
                } else {
                    state = PlaybackState.STOPPED;
                    return;
                }
            }
        }
        notifyObservers(nextSong);
    }

    public void previous() {
        Song prevSong;
        synchronized (lock) {
            if (queue.isEmpty()) return;
            prevSong = traversal.previous(queue, currentIndex);
            if (prevSong != null) {
                currentIndex = queue.indexOf(prevSong);
            } else {
                return;
            }
        }
        notifyObservers(prevSong);
    }

    public void setTraversal(QueueTraversalStrategy strategy) { this.traversal = strategy; }
    public void setRepeatMode(RepeatMode mode) { this.repeatMode = mode; }
    public void setRecommender(RecommendationStrategy strategy) { this.recommender = strategy; }
    public PlaybackState getState() { return state; }

    public Song getCurrentSong() {
        synchronized (lock) {
            return getCurrentSongLocked();
        }
    }

    private Song getCurrentSongLocked() {
        return (currentIndex >= 0 && currentIndex < queue.size()) ? queue.get(currentIndex) : null;
    }

    public List<Song> getRecommendations(List<Song> library, int count) {
        return recommender.recommend(getCurrentSong(), library, count);
    }

    public void addObserver(PlayerObserver o) { observers.add(o); }

    private void notifyObservers(Song song) {
        if (song == null) return;
        synchronized (lock) {
            // Avoid back-to-back duplicate entries (e.g. REPEAT_ONE calling
            // next() repeatedly shouldn't flood history with the same song).
            if (playHistory.peekFirst() == null || !playHistory.peekFirst().equals(song)) {
                playHistory.addFirst(song);
                if (playHistory.size() > MAX_HISTORY) playHistory.removeLast();
            }
        }
        for (PlayerObserver o : observers) o.onSongChanged(song);
    }
}


class UserManager {
    private final Map<String, User> users = new java.util.concurrent.ConcurrentHashMap<>();
    // One MusicPlayer per user - each gets its own queue, playback state,
    // and play history, isolated from every other user.
    private final Map<String, MusicPlayer> playersByUser = new java.util.concurrent.ConcurrentHashMap<>();

    public void registerUser(User user) {
        users.put(user.getId(), user);
    }

    public User getUser(String userId) {
        return users.get(userId);
    }

    // computeIfAbsent guarantees exactly one MusicPlayer is created per
    // user even if called concurrently from multiple threads.
    public MusicPlayer getPlayerFor(String userId) {
        return playersByUser.computeIfAbsent(userId, id -> new MusicPlayer());
    }
}



public class MusicPlayerWithRecommendationDemo {
    public static void main(String[] args) {
        Song s1 = new Song("1", "Song A", "Artist X", "Pop", 200);
        Song s2 = new Song("2", "Song B", "Artist Y", "Rock", 180);
        Song s3 = new Song("3", "Song C", "Artist X", "Pop", 220);
        List<Song> library = Arrays.asList(s1, s2, s3);

        User alice = new User("u1", "Alice");
        User bob = new User("u2", "Bob");

        UserManager userManager = new UserManager();
        userManager.registerUser(alice);
        userManager.registerUser(bob);

        Playlist alicePlaylist = new Playlist("p1", alice.getId(), "Alice's Mix");
        for (Song s : library) alicePlaylist.addSong(s);

        Playlist bobPlaylist = new Playlist("p2", bob.getId(), "Bob's Mix");
        bobPlaylist.addSong(s2);
        bobPlaylist.addSong(s3);

        // Each user gets their own isolated MusicPlayer via UserManager -
        // no shared state between Alice and Bob.
        MusicPlayer alicePlayer = userManager.getPlayerFor(alice.getId());
        MusicPlayer bobPlayer = userManager.getPlayerFor(bob.getId());

        alicePlayer.addObserver(song -> System.out.println("[Alice] Now playing: " + song));
        bobPlayer.addObserver(song -> System.out.println("[Bob] Now playing: " + song));

        alicePlayer.loadPlaylist(alicePlaylist);
        alicePlayer.play();                      // [Alice] Now playing: Song A - Artist X
        alicePlayer.next();                       // [Alice] Now playing: Song B - Artist Y

        bobPlayer.loadPlaylist(bobPlaylist);
        bobPlayer.play();                         // [Bob] Now playing: Song B - Artist Y

        // Proof of isolation: Alice's history should NOT contain Bob's plays
        System.out.println("Alice history: " + alicePlayer.getHistory());
        System.out.println("Bob history:   " + bobPlayer.getHistory());

        // Same instance returned on repeated lookup for the same user
        MusicPlayer aliceAgain = userManager.getPlayerFor(alice.getId());
        System.out.println("Same player instance for Alice: " + (alicePlayer == aliceAgain));

        alicePlayer.setRepeatMode(RepeatMode.REPEAT_ONE);
        alicePlayer.next();                       // [Alice] Now playing: Song B - Artist Y (repeat)

        System.out.println("Alice recommendations: " + alicePlayer.getRecommendations(library, 2));

        // Live extension demo: swap in a new recommender with zero core changes
        alicePlayer.setRecommender(new CollaborativeFilterStrategy());
        System.out.println("Alice collaborative recs: " + alicePlayer.getRecommendations(library, 2));

        // RecentlyPlayedStrategy demo: history is populated automatically by
        // the player itself (see notifyObservers) and shared by reference.
        alicePlayer.setRecommender(new RecentlyPlayedStrategy(alicePlayer.getHistory()));
        System.out.println("Alice recently-played recs: " + alicePlayer.getRecommendations(library, 2));

        // Live extension demo: swap traversal strategy
        alicePlayer.setRepeatMode(RepeatMode.NONE);
        alicePlayer.setTraversal(new ShuffleTraversal());
        alicePlayer.next();

        // Edge case: empty queue
        MusicPlayer emptyPlayer = new MusicPlayer();
        emptyPlayer.play();                      // no-op, does not throw
        System.out.println("Empty player state: " + emptyPlayer.getState());
    }
}
