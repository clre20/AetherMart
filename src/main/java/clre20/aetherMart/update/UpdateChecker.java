package clre20.aetherMart.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub Releases 版本檢查：只用一條背景執行緒，結果快取 10 分鐘（失敗 1 分鐘），沒有定時輪詢。
 * 標籤的 v 前綴會去掉再逐段比數字。
 */
public final class UpdateChecker {

    public enum State { LATEST, OUTDATED, DEVELOPMENT, NO_RELEASE, FAILED }

    public record Result(State state, String current, String latest, String url, String error) {
    }

    private static final String DEFAULT_API = "https://api.github.com/repos/clre20/AetherMart/releases/latest";
    private static final long CACHE_MS = 10 * 60_000L;
    private static final long FAIL_CACHE_MS = 60_000L;

    private final String current;
    private final String api;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherMart-Update");
        t.setDaemon(true);
        return t;
    });
    private volatile Result cached;
    private volatile long cachedAt;
    private CompletableFuture<Result> inflight;

    public UpdateChecker(String current) {
        this.current = current;
        String override = System.getProperty("aethermart.update-api");
        this.api = override == null || override.isBlank() ? DEFAULT_API : override.trim();
    }

    public Result cached() {
        return cached;
    }

    public synchronized CompletableFuture<Result> check() {
        Result c = cached;
        long age = System.currentTimeMillis() - cachedAt;
        if (c != null && age < (c.state() == State.FAILED ? FAIL_CACHE_MS : CACHE_MS)) {
            return CompletableFuture.completedFuture(c);
        }
        if (inflight != null && !inflight.isDone()) {
            return inflight;
        }
        inflight = CompletableFuture.supplyAsync(this::fetch, thread).thenApply(r -> {
            cached = r;
            cachedAt = System.currentTimeMillis();
            return r;
        });
        return inflight;
    }

    private Result fetch() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "AetherMart/" + current).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                return new Result(State.NO_RELEASE, current, null, null, null);
            }
            if (res.statusCode() != 200) {
                return new Result(State.FAILED, current, null, null, "HTTP " + res.statusCode());
            }
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : null;
            String url = o.has("html_url") ? o.get("html_url").getAsString() : "https://github.com/clre20/AetherMart/releases";
            if (tag == null || tag.isBlank()) {
                return new Result(State.NO_RELEASE, current, null, null, null);
            }
            String latest = strip(tag);
            int cmp = compare(strip(current), latest);
            State s = cmp < 0 ? State.OUTDATED : cmp > 0 ? State.DEVELOPMENT : State.LATEST;
            return new Result(s, current, latest, url, null);
        } catch (Exception e) {
            return new Result(State.FAILED, current, null, null, e.getClass().getSimpleName());
        }
    }

    static String strip(String v) {
        String s = v.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** 逐段比數字；非數字部分視為 0。 */
    public static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void shutdown() {
        thread.shutdownNow();
    }
}
