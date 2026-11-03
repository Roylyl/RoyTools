package com.github.kr328.clash.log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process owns this store. Clearing invalidates queued writes without stopping collection. */
public final class UnifiedLogStorage {
    public static final class Entry {
        public final long time;
        public final String level;
        public final String message;
        Entry(long time, String level, String message) {
            this.time = time; this.level = level; this.message = message;
        }
    }

    private static final long MAX_FILE_BYTES = 2_000_000L;
    private static final int MAX_READ_ENTRIES = 2_000;
    private static final class Recent {
        final ArrayDeque<Entry> entries = new ArrayDeque<>();
        int characters;
        void add(Entry entry) {
            entries.addLast(entry); characters += entry.message.length();
            while (entries.size() > MAX_READ_ENTRIES || characters > 2_000_000) {
                characters -= entries.removeFirst().message.length();
            }
        }
    }
    private final File directory;
    private final Object lock = new Object();
    private volatile Thread writerThread;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RoyTools-LogWriter");
        thread.setDaemon(true); writerThread = thread; return thread;
    });
    private long generation;
    private long clearCutoff;
    private final Map<String, Recent> recent = new HashMap<>();

    public UnifiedLogStorage(File directory) {
        this.directory = directory;
        File cutoff = new File(directory, "cleared-at");
        if (cutoff.isFile()) try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(cutoff), StandardCharsets.UTF_8))) {
            clearCutoff = Long.parseLong(reader.readLine());
        } catch (IOException | IllegalArgumentException ignored) { }
    }

    public void append(String module, long at, String level, String message) {
        checkModule(module);
        final long submittedGeneration;
        synchronized (lock) {
            if (at < clearCutoff) return;
            submittedGeneration = generation;
        }
        // No disk work on a UI/Binder callback. Copy the record before queuing it.
        final String record = at + ":" + level + ":" + Base64.getEncoder().encodeToString(
                message.getBytes(StandardCharsets.UTF_8)) + "\n";
        writer.execute(() -> {
            synchronized (lock) {
                if (submittedGeneration != generation || at < clearCutoff) return;
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) return;
                    File file = new File(directory, module + ".log");
                    if (file.length() + record.length() > MAX_FILE_BYTES) {
                        File previous = new File(directory, module + ".previous.log");
                        if (previous.exists() && !previous.delete()) return;
                        if (file.exists() && !file.renameTo(previous)) return;
                    }
                    try (FileOutputStream output = new FileOutputStream(file, true)) {
                        output.write(record.getBytes(StandardCharsets.UTF_8));
                    }
                    Recent cache = recent.get(module);
                    if (cache != null) cache.add(new Entry(at, level, message));
                } catch (IOException ignored) {
                    // Logging must never fail an audio, proxy or display operation.
                }
            }
        });
    }

    public List<Entry> read(String module) {
        checkModule(module); awaitWrites();
        synchronized (lock) {
            Recent cache = recent.get(module);
            if (cache == null) {
                cache = new Recent();
                for (Entry entry : readFiles(module)) cache.add(entry);
                recent.put(module, cache);
            }
            return new ArrayList<>(cache.entries);
        }
    }

    public List<Entry> readAll(String module) {
        checkModule(module); awaitWrites();
        synchronized (lock) { return readFiles(module); }
    }

    public void flush() { awaitWrites(); }

    private List<Entry> readFiles(String module) {
        List<Entry> entries = new ArrayList<>();
        readFile(new File(directory, module + ".previous.log"), entries);
        readFile(new File(directory, module + ".log"), entries);
        return entries;
    }

    public void clearAll() {
        synchronized (lock) {
            generation++;
            clearCutoff = System.currentTimeMillis();
            recent.clear();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (file.isFile() && !file.delete()) throw new IllegalStateException("无法清空日志：" + file.getName());
            }
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建日志目录");
            try (FileOutputStream output = new FileOutputStream(new File(directory, "cleared-at"))) {
                output.write(Long.toString(clearCutoff).getBytes(StandardCharsets.UTF_8));
            } catch (IOException failed) { throw new IllegalStateException("无法保存日志清空时间", failed); }
        }
    }

    private void awaitWrites() {
        if (Thread.currentThread() == writerThread) return;
        try { writer.submit(() -> {}).get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException failed) { throw new IllegalStateException(failed.getCause()); }
    }

    private static void readFile(File file, List<Entry> entries) {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":", 3);
                if (parts.length != 3) continue;
                try {
                    entries.add(new Entry(Long.parseLong(parts[0]), parts[1], new String(
                            Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8)));
                } catch (IllegalArgumentException ignored) { /* Skip a truncated final record. */ }
            }
        } catch (IOException ignored) { }
    }

    private static void checkModule(String module) {
        if (!module.matches("music|proxy|refresh|app")) throw new IllegalArgumentException("Unknown log module");
    }
}
