package clre20.aetherMart.discord;

import clre20.aetherMart.AetherMart;
import clre20.aetherMart.pdf.Receipt;
import clre20.aetherMart.util.TimeUtil;
import clre20.aetherMart.util.Vars;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Discord 通知佇列：所有通知先排入（存在 SQLite），單一發送執行緒依序送出。
 * 平時每則間隔 send-interval-ms；佇列超過 peak-threshold 時進入尖峰模式，同一人 merge-window 內的通知合併成一則（PDF 合併成一份）。
 */
public final class Notifier {

    private static final long PERSONAL_EXPIRE = 24 * 3_600_000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_FILE = 8L * 1024 * 1024;
    /** 單一玩家排隊中的私訊上限（防止反覆上架／下架灌爆佇列）。 */
    private static final int MAX_PER_PLAYER = 30;
    /** 整個佇列的上限；超過時只收管理員與每日彙整。 */
    private static final int MAX_QUEUE = 5000;

    private final AetherMart plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Deque<Notice> queue = new ConcurrentLinkedDeque<>();
    private ScheduledExecutorService sender;

    public Notifier(AetherMart plugin) {
        this.plugin = plugin;
    }

    public void load() throws Exception {
        List<Notice> list = plugin.db().call(c -> {
            List<Notice> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, payload, attempts FROM notify_queue ORDER BY id")) {
                while (rs.next()) {
                    try {
                        Notice n = gson.fromJson(rs.getString(2), Notice.class);
                        n.id = rs.getLong(1);
                        n.attempts = rs.getInt(3);
                        out.add(n);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Dropped unreadable notice #" + rs.getLong(1));
                    }
                }
            }
            return out;
        }, 30);
        queue.addAll(list);
    }

    public void start() {
        long interval = plugin.config().sendIntervalMs;
        sender = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AetherMart-Discord");
            t.setDaemon(true);
            return t;
        });
        sender.scheduleWithFixedDelay(this::tickSafe, 3000, interval, TimeUnit.MILLISECONDS);
        sender.scheduleWithFixedDelay(() -> plugin.renderer().releaseIfIdle(), 1, 1, TimeUnit.MINUTES);
    }

    public int size() {
        return queue.size();
    }

    // ---------------- 排入 ----------------

    private boolean botConfigured() {
        return plugin.bot().configured();
    }

    public void personal(UUID player, String name, int flag, String key, Map<String, String> vars, Receipt receipt) {
        debugDump(receipt);
        if (!plugin.config().personalEnabled || !botConfigured() || Bukkit.getPluginManager().getPlugin("DiscordLink") == null) {
            return;
        }
        Notice n = new Notice();
        n.target = Notice.PERSONAL;
        n.player = player.toString();
        n.playerName = name;
        n.flag = flag;
        n.key = key;
        n.vars.putAll(vars);
        n.receipt = receipt;
        boolean penalty = key.endsWith(".penalty");
        if (!penalty && (queue.size() >= MAX_QUEUE || pendingFor(n.player) >= MAX_PER_PLAYER)) {
            plugin.activity().log("DISCORD", "skipped " + key + " to " + name + " (queue limit)");
            return;
        }
        enqueue(n);
    }

    private int pendingFor(String player) {
        int count = 0;
        for (Notice q : queue) {
            if (player.equals(q.player)) {
                count++;
            }
        }
        return count;
    }

    /** 測試用：-Daethermart.debug.receipts=true 時把每份個人明細另存到 debug-receipts/（平常不需要）。 */
    private void debugDump(Receipt receipt) {
        if (receipt == null || !Boolean.getBoolean("aethermart.debug.receipts")) {
            return;
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "debug-receipts");
        String gen = generated();
        plugin.worker().execute(() -> {
            try {
                java.nio.file.Files.createDirectories(dir.toPath());
                byte[] data = plugin.renderer().receipt(receipt, gen);
                java.nio.file.Files.write(new java.io.File(dir, receipt.kind + "-" + receipt.number + ".pdf").toPath(), data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "debug receipt failed", e);
            }
        });
    }

    public void admin(String key, Map<String, String> vars) {
        if (!botConfigured() || plugin.config().adminChannel.isEmpty() || !plugin.config().adminPerTrade) {
            return;
        }
        Notice n = new Notice();
        n.target = Notice.ADMIN;
        n.key = key;
        n.vars.putAll(vars);
        if (queue.size() >= MAX_QUEUE * 2) {
            plugin.activity().log("DISCORD", "skipped admin " + key + " (queue limit)");
            return;
        }
        enqueue(n);
    }

    public void daily(String fileName, Map<String, String> vars, boolean empty) {
        if (!botConfigured() || plugin.config().adminChannel.isEmpty()) {
            return;
        }
        Notice n = new Notice();
        n.target = Notice.DAILY;
        n.key = empty ? "discord.daily.empty" : "discord.daily.report";
        n.file = fileName;
        n.vars.putAll(vars);
        enqueue(n);
    }

    private void enqueue(Notice n) {
        n.created = System.currentTimeMillis();
        String json = gson.toJson(n);
        queue.add(n);
        plugin.db().submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO notify_queue(created,target,player,kind,payload,attempts) VALUES(?,?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, n.created);
                ps.setString(2, n.target);
                ps.setString(3, n.player);
                ps.setString(4, n.key);
                ps.setString(5, json);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        }).thenAccept(id -> n.id = id);
        plugin.activity().log("DISCORD", "queued " + n.target + " " + n.key + (n.playerName == null ? "" : " to " + n.playerName));
    }

    // ---------------- 發送 ----------------

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Discord sender error", t);
        }
    }

    private void tick() {
        if (!plugin.bot().ready()) {
            return;
        }
        Notice head = queue.peek();
        if (head == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (head.retryAt > now) {
            return;
        }
        if (!Notice.DAILY.equals(head.target) && now - head.created > PERSONAL_EXPIRE) {
            drop(head, "expired (older than 24h)");
            return;
        }
        List<Notice> group = new ArrayList<>();
        group.add(head);
        if (queue.size() > plugin.config().peakThreshold && !Notice.DAILY.equals(head.target)) {
            long until = head.created + plugin.config().mergeWindowMs;
            for (Notice n : queue) {
                if (n != head && n.created <= until && n.groupKey().equals(head.groupKey())) {
                    group.add(n);
                }
            }
        }
        try {
            boolean done = switch (head.target) {
                case Notice.PERSONAL -> sendPersonal(group);
                case Notice.ADMIN -> sendAdmin(group);
                default -> sendDaily(head);
            };
            if (done) {
                for (Notice n : group) {
                    remove(n);
                }
                plugin.activity().log("DISCORD", "sent " + head.target + " " + head.key + (group.size() > 1 ? " (merged " + group.size() + ")" : "")
                        + (head.playerName == null ? "" : " to " + head.playerName));
            }
        } catch (ErrorResponseException e) {
            ErrorResponse r = e.getErrorResponse();
            if (r == ErrorResponse.CANNOT_SEND_TO_USER || r == ErrorResponse.UNKNOWN_USER || r == ErrorResponse.UNKNOWN_CHANNEL
                    || r == ErrorResponse.MISSING_ACCESS || r == ErrorResponse.MISSING_PERMISSIONS) {
                for (Notice n : group) {
                    drop(n, "Discord refused: " + r.name());
                }
            } else {
                retry(group, e.getMessage());
            }
        } catch (Exception e) {
            retry(group, e.toString());
        }
    }

    private void retry(List<Notice> group, String why) {
        for (Notice n : group) {
            n.attempts++;
            if (n.attempts >= MAX_ATTEMPTS) {
                drop(n, "failed " + MAX_ATTEMPTS + " times: " + why);
            } else {
                n.retryAt = System.currentTimeMillis() + 5000L * n.attempts;
                long id = n.id;
                int attempts = n.attempts;
                plugin.db().execute(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE notify_queue SET attempts=? WHERE id=?")) {
                        ps.setInt(1, attempts);
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                });
            }
        }
    }

    private void drop(Notice n, String why) {
        remove(n);
        plugin.activity().log("DISCORD", "dropped " + n.target + " " + n.key + (n.playerName == null ? "" : " to " + n.playerName) + ": " + why);
    }

    private void remove(Notice n) {
        queue.remove(n);
        long id = n.id;
        if (id > 0) {
            plugin.db().execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM notify_queue WHERE id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            });
        }
    }

    private String generated() {
        return TimeUtil.format(System.currentTimeMillis(), plugin.config().zone, TimeUtil.DATE_TIME)
                + "（" + plugin.config().zone.getId() + "）";
    }

    private MessageEmbed embed(String key, Map<String, String> vars, Color color) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle(cut(plugin.messages().plain(key + ".title", vars), 256));
        eb.setDescription(cut(plugin.messages().plain(key + ".description", vars), 4000));
        eb.setColor(color);
        eb.setFooter(cut(plugin.messages().plain("discord.footer", Vars.of("server", plugin.pdf().serverName())), 2000));
        eb.setTimestamp(java.time.Instant.now());
        return eb.build();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 個人私訊（一則或尖峰合併的多則）。 */
    private boolean sendPersonal(List<Notice> group) throws Exception {
        Notice head = group.get(0);
        UUID player = UUID.fromString(head.player);
        int disabled = plugin.players().disabledNotifyDb(player).get(10, TimeUnit.SECONDS);
        List<Notice> keep = new ArrayList<>();
        for (Notice n : group) {
            if (n.flag != 0 && (disabled & n.flag) != 0) {
                remove(n);
                plugin.activity().log("DISCORD", "skipped " + n.key + " to " + n.playerName + ": turned off by player");
            } else {
                keep.add(n);
            }
        }
        if (keep.isEmpty()) {
            return false;
        }
        Optional<String> dc = plugin.links().discordId(player);
        if (dc.isEmpty()) {
            for (Notice n : keep) {
                drop(n, "player has no Discord link");
            }
            return false;
        }
        JDA jda = plugin.bot().jda();
        if (jda == null) {
            throw new IllegalStateException("bot not connected");
        }
        MessageEmbed embed;
        byte[] pdf;
        String fileName;
        if (keep.size() == 1) {
            Notice n = keep.get(0);
            Color color = n.receipt == null ? plugin.pdf().theme() : plugin.pdf().type(n.receipt.kind).color();
            embed = embed(n.key, n.vars, color);
            pdf = n.receipt == null ? null : plugin.renderer().receipt(n.receipt, generated());
            fileName = n.receipt == null ? null : "AetherMart-" + n.receipt.number + ".pdf";
        } else {
            StringBuilder lines = new StringBuilder();
            List<Receipt> receipts = new ArrayList<>();
            for (Notice n : keep) {
                lines.append(plugin.messages().plain("discord.dm.combined.line", Vars.with(n.vars,
                        "title", plugin.messages().plain(n.key + ".title", n.vars)))).append('\n');
                if (n.receipt != null) {
                    receipts.add(n.receipt);
                }
            }
            String period = TimeUtil.format(keep.get(0).created, plugin.config().zone, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    + " ～ " + TimeUtil.format(keep.get(keep.size() - 1).created, plugin.config().zone, DateTimeFormatter.ofPattern("HH:mm"));
            embed = embed("discord.dm.combined", Vars.of("player", head.playerName, "count", keep.size(), "lines", lines.toString().trim()),
                    plugin.pdf().theme());
            String number = receipts.isEmpty() ? "" : receipts.get(0).number + "-M";
            pdf = receipts.isEmpty() ? null : plugin.renderer().combined(receipts, number, head.playerName, period, generated());
            fileName = "AetherMart-" + number + ".pdf";
        }
        User user = jda.retrieveUserById(dc.get()).complete();
        var ch = user.openPrivateChannel().complete();
        var action = ch.sendMessageEmbeds(embed);
        if (pdf != null && pdf.length <= MAX_FILE) {
            action = action.addFiles(FileUpload.fromData(pdf, fileName));
        }
        action.complete();
        group.clear();
        group.addAll(keep);
        return true;
    }

    private GuildMessageChannel adminChannel(JDA jda) {
        String id = plugin.config().adminChannel;
        if (!id.matches("\\d{5,25}")) {
            return null;
        }
        return jda.getChannelById(GuildMessageChannel.class, id);
    }

    private boolean sendAdmin(List<Notice> group) {
        JDA jda = plugin.bot().jda();
        GuildMessageChannel ch = jda == null ? null : adminChannel(jda);
        if (ch == null) {
            for (Notice n : new ArrayList<>(group)) {
                drop(n, "admin channel not found");
            }
            return false;
        }
        MessageEmbed embed;
        if (group.size() == 1) {
            embed = embed(group.get(0).key, group.get(0).vars, plugin.pdf().theme());
        } else {
            StringBuilder lines = new StringBuilder();
            for (Notice n : group) {
                lines.append("• ").append(plugin.messages().plain(n.key + ".title", n.vars)).append(" — ")
                        .append(plugin.messages().plain(n.key + ".description", n.vars).replace('\n', ' ')).append('\n');
            }
            embed = embed("discord.admin.combined", Vars.of("count", group.size(), "lines", lines.toString().trim()), plugin.pdf().theme());
        }
        ch.sendMessageEmbeds(embed).complete();
        return true;
    }

    private boolean sendDaily(Notice n) throws Exception {
        JDA jda = plugin.bot().jda();
        GuildMessageChannel ch = jda == null ? null : adminChannel(jda);
        if (ch == null) {
            drop(n, "admin channel not found");
            return false;
        }
        MessageEmbed embed = embed(n.key, n.vars, plugin.pdf().theme());
        List<FileUpload> files = new ArrayList<>();
        if (n.file != null && n.file.matches("[A-Za-z0-9._-]{1,80}\\.pdf")) {
            File f = new File(new File(plugin.getDataFolder(), "reports"), n.file);
            if (f.isFile()) {
                byte[] data = Files.readAllBytes(f.toPath());
                if (data.length <= MAX_FILE) {
                    files.add(FileUpload.fromData(data, n.file));
                } else {
                    files.addAll(split(data, n.file));
                }
            }
        }
        if (files.isEmpty()) {
            ch.sendMessageEmbeds(embed).complete();
        } else {
            ch.sendMessageEmbeds(embed).addFiles(files.get(0)).complete();
            for (int i = 1; i < files.size(); i++) {
                ch.sendFiles(files.get(i)).complete();
            }
        }
        return true;
    }

    /** 太大的 PDF 依頁數平均切成多個檔案。 */
    private List<FileUpload> split(byte[] data, String name) throws Exception {
        List<FileUpload> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(data)) {
            int parts = (int) Math.ceil(data.length / (double) (MAX_FILE - 512 * 1024));
            int pages = doc.getNumberOfPages();
            int per = Math.max(1, (int) Math.ceil(pages / (double) parts));
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage(per);
            List<PDDocument> docs = splitter.split(doc);
            String base = name.substring(0, name.length() - 4);
            for (int i = 0; i < docs.size(); i++) {
                try (PDDocument d = docs.get(i)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    d.save(bos);
                    out.add(FileUpload.fromData(bos.toByteArray(), base + "-" + (i + 1) + "of" + docs.size() + ".pdf"));
                }
            }
        }
        return out;
    }

    public void shutdown() {
        if (sender != null) {
            sender.shutdown();
            try {
                sender.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
