package com.learn.machine.coding;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/* ============================================================
   LOGGING FRAMEWORK - Machine Coding
   Matches the supplied UML: LogManager (singleton), hierarchical
   Logger (parent/additivity, Log4j-style), AsyncLogProcessor
   (bounded-queue executor), LogAppender/LogFormatter (Strategy).
   ============================================================ */

// ---------- LogLevel ----------

enum LogLevel { DEBUG, INFO, WARN, ERROR, FATAL }

// ---------- LogMessage ----------

class LogMessage {
    private final LogLevel level;
    private final String message;
    private final String threadName;
    private final LocalDateTime timestamp;
    private final String loggerName;

    public LogMessage(LogLevel level, String message, String loggerName) {
        this.level = level;
        this.message = message;
        this.threadName = Thread.currentThread().getName();
        this.timestamp = LocalDateTime.now();
        this.loggerName = loggerName;
    }

    public LogLevel getLevel() { return level; }
    public String getMessage() { return message; }
    public String getThreadName() { return threadName; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getLoggerName() { return loggerName; }
}

// ---------- LogFormatter (Strategy) ----------

interface LogFormatter {
    String format(LogMessage message);
}

class SimpleTextFormatter implements LogFormatter {
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public String format(LogMessage m) {
        return String.format("%s [%s] %s %s - %s",
                m.getTimestamp().format(DATE_TIME_FORMATTER),
                m.getThreadName(), m.getLevel(), m.getLoggerName(), m.getMessage());
    }
}

// ---------- LogAppender (Strategy) ----------

interface LogAppender {
    void setFormatter(LogFormatter formatter);
    LogFormatter getFormatter();
    void append(LogMessage message);
    void close();
}

class ConsoleAppender implements LogAppender {
    private LogFormatter formatter;

    public ConsoleAppender(LogFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public void setFormatter(LogFormatter formatter) { this.formatter = formatter; }
    @Override
    public LogFormatter getFormatter() { return formatter; }

    @Override
    public void append(LogMessage message) {
        System.out.println(formatter.format(message));
    }

    @Override
    public void close() {
    }
}

class FileAppender implements LogAppender {
    private LogFormatter formatter;
    private final FileWriter writer;

    public FileAppender(LogFormatter formatter, String filePath) throws IOException {
        this.formatter = formatter;
        this.writer = new FileWriter(filePath, true);
    }

    @Override
    public void setFormatter(LogFormatter formatter) { this.formatter = formatter; }
    @Override
    public LogFormatter getFormatter() { return formatter; }

    @Override
    public void append(LogMessage message) {
        try {
            writer.write(formatter.format(message) + System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write log record", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to close FileAppender: " + e.getMessage());
        }
    }
}

// ---------- AsyncLogProcessor: bounded queue, graceful shutdown ----------

class AsyncLogProcessor {
    private final ExecutorService executor;

    public AsyncLogProcessor() {
        this(1000);
    }

    public AsyncLogProcessor(int queueCapacity) {
        ThreadFactory daemonThreadFactory = runnable -> {
            Thread t = new Thread(runnable, "log-dispatcher");
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonThreadFactory,
                (rejectedTask, exec) -> System.err.println(
                        "[AsyncLogProcessor] dropping a log record (queue full or shutting down)")
        );
    }

    public void process(LogMessage message, List<LogAppender> appenders) {
        executor.execute(() -> {
            for (LogAppender appender : appenders) {
                appender.append(message);
            }
        });
    }

    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[AsyncLogProcessor] backlog didn't drain in time, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

// ---------- Logger: hierarchical, Log4j-style ----------

class Logger {
    private final Logger parent;
    private final List<LogAppender> appenders = new CopyOnWriteArrayList<>();
    private final String name;
    private volatile LogLevel level;
    private volatile boolean additivity = true;

    Logger(Logger parent, String name, LogLevel level) {
        this.parent = parent;
        this.name = name;
        this.level = level;
    }

    public LogLevel getEffectiveLevel() {
        if (level != null) return level;
        return parent != null ? parent.getEffectiveLevel() : LogLevel.INFO;
    }

    public void setLevel(LogLevel level) { this.level = level; }
    public void setAdditivity(boolean additivity) { this.additivity = additivity; }
    public void addAppender(LogAppender appender) { appenders.add(appender); }

    public void log(LogLevel msgLevel, String message) {
        if (msgLevel.ordinal() < getEffectiveLevel().ordinal()) return;
        LogMessage logMessage = new LogMessage(msgLevel, message, name);
        callAppenders(logMessage);
    }

    private void callAppenders(LogMessage message) {
        if (!appenders.isEmpty()) {
            List<LogAppender> snapshot = new ArrayList<>(appenders);
            LogManager.getInstance().getProcessor().process(message, snapshot);
        }
        if (additivity && parent != null) {
            parent.callAppenders(message);
        }
    }

    public void debug(String message) { log(LogLevel.DEBUG, message); }
    public void info(String message)  { log(LogLevel.INFO, message); }
    public void warn(String message)  { log(LogLevel.WARN, message); }
    public void error(String message) { log(LogLevel.ERROR, message); }
    public void fatal(String message) { log(LogLevel.FATAL, message); }
}

// ---------- LogManager: singleton, owns the logger tree + processor ----------

class LogManager {
    private static final LogManager INSTANCE = new LogManager();

    private final Logger rootLogger;
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final AsyncLogProcessor processor;

    private LogManager() {
        this.processor = new AsyncLogProcessor();
        this.rootLogger = new Logger(null, "root", LogLevel.INFO);
        loggers.put("root", rootLogger);
    }

    public static LogManager getInstance() { return INSTANCE; }

    private Logger createLogger(String name) {
        Logger parent = resolveParent(name);
        return new Logger(parent, name, null);
    }

    private Logger resolveParent(String name) {
        int idx = name.lastIndexOf('.');
        while (idx > 0) {
            String candidateName = name.substring(0, idx);
            Logger candidate = loggers.get(candidateName);
            if (candidate != null) return candidate;
            idx = name.lastIndexOf('.', idx - 1);
        }
        return rootLogger;
    }

    public Logger getLogger(String name) {
        if (name.equals("root")) return rootLogger;
        return loggers.computeIfAbsent(name, this::createLogger);
    }

    public void shutdown() {
        processor.stop();
    }

    AsyncLogProcessor getProcessor() { return processor; }
}

// ---------- Demo Harness ----------

public class LoggerFrameworkDemo {
    public static void main(String[] args) throws Exception {
        LogManager manager = LogManager.getInstance();

        // ---- Basic usage ----
        Logger appLogger = manager.getLogger("com.app");
        appLogger.setLevel(LogLevel.INFO);
        String appLogPath = "app.log";
        FileAppender appFileAppender = new FileAppender(new SimpleTextFormatter(), appLogPath);
        appLogger.addAppender(new ConsoleAppender(new SimpleTextFormatter()));
        appLogger.addAppender(appFileAppender);

        appLogger.debug("this should NOT appear - below threshold");
        appLogger.info("Application started");
        appLogger.warn("cache miss rate elevated");
        appLogger.error("An error occurred");
        appLogger.fatal("unrecoverable state");

        // ---- Hierarchy + additivity demo ----
        // "com.app.service" has no explicit level or appenders of its own
        // - it inherits com.app's effective level, and its logs also
        // reach com.app's appenders via additivity.
        Logger serviceLogger = manager.getLogger("com.app.service");
        System.out.println("\nserviceLogger effective level (inherited): " + serviceLogger.getEffectiveLevel());
        serviceLogger.info("service-level event, should also land in com.app's file");

        // ---- Additivity = false demo ----
        Logger isolatedLogger = manager.getLogger("com.app.isolated");
        isolatedLogger.setAdditivity(false);
        String isolatedLogPath = "isolated.log";
        FileAppender isolatedFileAppender = new FileAppender(new SimpleTextFormatter(), isolatedLogPath);
        isolatedLogger.addAppender(isolatedFileAppender);
        isolatedLogger.info("this should NOT reach com.app's file");

        // ---- Bounded-queue + graceful shutdown proof ----
        Logger burstLogger = manager.getLogger("burst");
        burstLogger.setAdditivity(false); // keep isolated from root/com.app
        String burstLogPath = "burst.log";
        FileAppender burstFileAppender = new FileAppender(new SimpleTextFormatter(), burstLogPath);
        burstLogger.addAppender(burstFileAppender);
        for (int i = 0; i < 50; i++) {
            burstLogger.info("burst message " + i);
        }

        manager.shutdown(); // blocks until the whole backlog is drained

        appFileAppender.close();
        isolatedFileAppender.close();
        burstFileAppender.close();

    }
}