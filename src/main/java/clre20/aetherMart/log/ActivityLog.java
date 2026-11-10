package clre20.aetherMart.log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * 全方位紀錄：plugins/AetherMart/logs/aethermart-YYYY-MM-DD.log，一行一筆。
 * 緩衝後每 5 秒寫一次；換日或超過大小時壓縮成 .log.gz 並開新檔；過期或總量超標時從最舊的刪。
 */
public final class ActivityLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_QUEUE = 100_000;

    private final File dir;
    private final Logger logger;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AetherMart-Log");
        t.setDaemon(true);
        return t;
    });
    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile long maxFileBytes = 10L * 1024 * 1024;
    private volatile int retentionDays = 90;
    private volatile long maxTotalBytes = 500L * 1024 * 1024;
    private LocalDate currentDate;
    private Path currentFile;
    private final java.util.concurrent.atomic.AtomicInteger size = new java.util.concurrent.atomic.AtomicInteger();

    public ActivityLog(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "logs");
        this.logger = logger;
    }

    public void configure(ZoneId zone, long maxFileBytes, int retentionDays, long maxTotalBytes) {
        this.zone = zone;
        this.maxFileBytes = maxFileBytes;
        this.retentionDays = retentionDays;
        this.maxTotalBytes = maxTotalBytes;
    }

    public void start() {
        io.scheduleWithFixedDelay(this::flushSafe, 5, 5, TimeUnit.SECONDS);
        io.scheduleWithFixedDelay(this::cleanupSafe, 0, 6, TimeUnit.HOURS);
    }

    /** 記一筆；類別例如 TRADE、ADMIN、CLAIM、DISCORD、ERROR。 */
    public void log(String category, String text) {
        if (size.get() >= MAX_QUEUE) {
            return;
        }
        String line = "[" + ZonedDateTime.now(zone).format(TS) + "] [" + category + "] "
                + text.replace('\n', ' ').replace('\r', ' ');
        queue.add(line);
        size.incrementAndGet();
    }

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Cannot write activity log", t);
        }
    }

    private void flush() throws IOException {
        if (queue.isEmpty()) {
            return;
        }
        Files.createDirectories(dir.toPath());
        LocalDate today = LocalDate.now(zone);
        if (currentFile == null || !today.equals(currentDate)) {
            rotate(today, false);
        } else if (Files.exists(currentFile) && Files.size(currentFile) >= maxFileBytes) {
            rotate(today, true);
        }
        StringBuilder sb = new StringBuilder();
        String line;
        int n = 0;
        while ((line = queue.poll()) != null) {
            sb.append(line).append('\n');
            n++;
            if (sb.length() > 1 << 20) {
                break;
            }
        }
        size.addAndGet(-n);
        Files.writeString(currentFile, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** 換檔：把舊檔壓縮，開新檔。 */
    private void rotate(LocalDate today, boolean bySize) throws IOException {
        if (currentFile != null && Files.exists(currentFile) && (bySize || !today.equals(currentDate))) {
            compress(currentFile);
        }
        // 前一次執行留下、還沒壓縮的舊檔
        try (Stream<Path> s = Files.list(dir.toPath())) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".log") && !n.startsWith("aethermart-" + today)) {
                    compress(p);
                }
            }
        }
        currentDate = today;
        Path base = dir.toPath().resolve("aethermart-" + today + ".log");
        if (bySize || (Files.exists(base) && Files.size(base) >= maxFileBytes)) {
            int i = 2;
            Path p;
            do {
                p = dir.toPath().resolve("aethermart-" + today + "-" + i + ".log");
                i++;
            } while (Files.exists(p) || Files.exists(p.resolveSibling(p.getFileName() + ".gz")));
            if (Files.exists(base)) {
                compress(base);
            }
            currentFile = p;
        } else {
            currentFile = base;
        }
    }

    private void compress(Path p) {
        Path gz = p.resolveSibling(p.getFileName() + ".gz");
        try (InputStream in = Files.newInputStream(p); OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            in.transferTo(out);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot compress " + p.getFileName(), e);
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private void cleanupSafe() {
        try {
            cleanup();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Cannot clean activity logs", t);
        }
    }

    private void cleanup() throws IOException {
        if (!dir.isDirectory()) {
            return;
        }
        List<Path> archives = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir.toPath())) {
            s.filter(p -> p.getFileName().toString().endsWith(".log.gz")).forEach(archives::add);
        }
        archives.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
        long now = System.currentTimeMillis();
        if (retentionDays > 0) {
            long cutoff = now - retentionDays * 86_400_000L;
            for (Path p : new ArrayList<>(archives)) {
                if (p.toFile().lastModified() < cutoff) {
                    Files.deleteIfExists(p);
                    archives.remove(p);
                }
            }
        }
        if (maxTotalBytes > 0) {
            long total = 0;
            try (Stream<Path> s = Files.list(dir.toPath())) {
                for (Path p : s.toList()) {
                    total += Files.size(p);
                }
            }
            for (Path p : archives) {
                if (total <= maxTotalBytes) {
                    break;
                }
                total -= Files.size(p);
                Files.deleteIfExists(p);
            }
        }
    }

    /** 寫完緩衝並停止。 */
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            flush();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot write activity log", e);
        }
    }
}
