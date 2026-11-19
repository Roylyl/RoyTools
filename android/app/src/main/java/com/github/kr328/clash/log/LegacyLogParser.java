package com.github.kr328.clash.log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Streaming reader for the former timestamp:Level:message files. */
public final class LegacyLogParser {
    private LegacyLogParser() { }

    public static void forEach(File file, Consumer<UnifiedLogStorage.Entry> consumer) throws IOException {
        long lastTime = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":", 3);
                long at;
                try { at = Long.parseLong(parts[0]); }
                catch (NumberFormatException invalid) {
                    consumer.accept(new UnifiedLogStorage.Entry(lastTime, "Warning", line));
                    continue;
                }
                lastTime = at;
                String level = parts.length > 1 && parts[1].matches("Debug|Info|Warning|Error|Silent|Unknown")
                        ? parts[1] : "Warning";
                consumer.accept(new UnifiedLogStorage.Entry(at, level, parts.length == 3 ? parts[2] : line));
            }
        }
    }
}
