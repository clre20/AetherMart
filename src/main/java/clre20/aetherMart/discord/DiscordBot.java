package clre20.aetherMart.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** AetherMart 自己的 Discord 機器人（用戶在 config 填 Token）。連線在背景執行緒，不卡伺服器啟動。 */
public final class DiscordBot {

    private final Logger logger;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherMart-DiscordLogin");
        t.setDaemon(true);
        return t;
    });
    private volatile JDA jda;
    private volatile String token = "";

    public DiscordBot(Logger logger) {
        this.logger = logger;
    }

    public boolean ready() {
        JDA j = jda;
        return j != null && j.getStatus() == JDA.Status.CONNECTED;
    }

    public JDA jda() {
        return jda;
    }

    public boolean configured() {
        return !token.isEmpty();
    }

    /** 啟動或在 Token 改變時重新連線。 */
    public void apply(String newToken) {
        String t = newToken == null ? "" : newToken.trim();
        if (t.equals(token) && (jda != null || t.isEmpty())) {
            return;
        }
        token = t;
        lifecycle.execute(() -> {
            stop();
            if (t.isEmpty()) {
                return;
            }
            try {
                JDA j = JDABuilder.createLight(t, java.util.EnumSet.noneOf(GatewayIntent.class))
                        .setEnableShutdownHook(false)
                        .setAutoReconnect(true)
                        .build();
                j.awaitReady();
                jda = j;
                logger.info("Discord bot connected as " + j.getSelfUser().getName());
            } catch (Exception e) {
                logger.warning("Discord bot could not connect (notifications paused, shop still works): " + e.getMessage());
            }
        });
    }

    private void stop() {
        JDA j = jda;
        jda = null;
        if (j == null) {
            return;
        }
        j.shutdown();
        try {
            if (!j.awaitShutdown(5, TimeUnit.SECONDS)) {
                j.shutdownNow();
            }
        } catch (InterruptedException e) {
            j.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        lifecycle.shutdownNow();
        stop();
    }
}
