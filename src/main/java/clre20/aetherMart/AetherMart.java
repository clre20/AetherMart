package clre20.aetherMart;

import clre20.aetherMart.auction.AuctionService;
import clre20.aetherMart.auction.BannedItems;
import clre20.aetherMart.command.MartCommand;
import clre20.aetherMart.config.MainConfig;
import clre20.aetherMart.config.MarketConfig;
import clre20.aetherMart.config.Messages;
import clre20.aetherMart.config.PdfConfig;
import clre20.aetherMart.currency.CurrencyRegistry;
import clre20.aetherMart.data.Database;
import clre20.aetherMart.data.MetaStore;
import clre20.aetherMart.data.TradeStore;
import clre20.aetherMart.discord.DiscordBot;
import clre20.aetherMart.discord.LinkReader;
import clre20.aetherMart.discord.Notifier;
import clre20.aetherMart.gui.ChatInput;
import clre20.aetherMart.gui.Icons;
import clre20.aetherMart.gui.MenuListener;
import clre20.aetherMart.gui.Sounds;
import clre20.aetherMart.gui.Tickable;
import clre20.aetherMart.lang.ItemNames;
import clre20.aetherMart.log.ActivityLog;
import clre20.aetherMart.pdf.PdfRenderer;
import clre20.aetherMart.pdf.ReceiptFactory;
import clre20.aetherMart.player.ClaimService;
import clre20.aetherMart.player.PlayerListener;
import clre20.aetherMart.player.PlayerService;
import clre20.aetherMart.player.TradeGuard;
import clre20.aetherMart.report.DailyReport;
import clre20.aetherMart.shop.ShopService;
import clre20.aetherMart.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** AetherMart：系統商店＋玩家拍賣。 */
public final class AetherMart extends JavaPlugin {

    private final MainConfig config = new MainConfig();
    private final MarketConfig market = new MarketConfig();
    private final Messages messages = new Messages();
    private final PdfConfig pdf = new PdfConfig();
    private ExecutorService worker;
    private ActivityLog activity;
    private Database db;
    private MetaStore meta;
    private TradeStore trades;
    private CurrencyRegistry currencies;
    private ItemNames names;
    private PlayerService players;
    private ClaimService claims;
    private TradeGuard guard;
    private ShopService shop;
    private BannedItems banned;
    private AuctionService auctions;
    private ReceiptFactory receipts;
    private PdfRenderer renderer;
    private DiscordBot bot;
    private LinkReader links;
    private Notifier notifier;
    private DailyReport daily;
    private ChatInput input;
    private Icons icons;
    private Sounds sounds;
    private UpdateChecker updates;
    private boolean ready;
    private String loadedLanguage;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AetherMart-Worker");
            t.setDaemon(true);
            return t;
        });
        try {
            loadConfigs();
            activity = new ActivityLog(getDataFolder(), getLogger());
            activity.configure(config.zone, config.logMaxFileBytes, config.logRetentionDays, config.logMaxTotalBytes);
            activity.start();

            db = new Database(new File(getDataFolder(), "data.db"), getLogger());
            db.open();
            meta = new MetaStore(db);
            meta.load();
            trades = new TradeStore(db);

            currencies = new CurrencyRegistry(getLogger());
            currencies.load(config);
            names = new ItemNames(this);
            names.load(config.language, worker);
            loadedLanguage = config.language;

            icons = new Icons(this);
            sounds = new Sounds(this);
            input = new ChatInput(this);
            players = new PlayerService(this, db);
            claims = new ClaimService(this);
            guard = new TradeGuard(this);
            receipts = new ReceiptFactory(this);
            renderer = new PdfRenderer(this, () -> pdf, () -> config.language);
            bot = new DiscordBot(getLogger());
            links = new LinkReader(this);
            notifier = new Notifier(this);
            notifier.load();

            shop = new ShopService(this);
            shop.load();
            banned = new BannedItems(db, getLogger());
            banned.load();
            auctions = new AuctionService(this);
            auctions.load();
            daily = new DailyReport(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "AetherMart could not start", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(input, this);
        pm.registerEvents(new PlayerListener(this), this);

        PluginCommand cmd = getCommand("aethermart");
        if (cmd != null) {
            MartCommand mc = new MartCommand(this);
            cmd.setExecutor(mc);
            cmd.setTabCompleter(mc);
        }

        bot.apply(config.token);
        notifier.start();
        links.start();
        daily.start();
        Bukkit.getScheduler().runTaskTimer(this, this::tickMenus, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(this, currencies::checkAll, 40L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerListener.loadPlayer(this, p, false);
        }

        updates = new UpdateChecker(getPluginMeta().getVersion());
        if (config.updateOnStartup) {
            updates.check().thenAccept(r -> {
                switch (r.state()) {
                    case OUTDATED -> getLogger().warning(messages.plain("update.console-outdated", clre20.aetherMart.util.Vars.of(
                            "current", r.current(), "latest", r.latest(), "url", r.url())));
                    case DEVELOPMENT -> getLogger().info(messages.plain("update.console-development", clre20.aetherMart.util.Vars.of(
                            "current", r.current(), "latest", r.latest())));
                    case LATEST -> getLogger().info(messages.plain("update.console-latest", clre20.aetherMart.util.Vars.of("current", r.current())));
                    case NO_RELEASE -> getLogger().info(messages.plain("update.console-no-release", java.util.Map.of()));
                    case FAILED -> getLogger().warning(messages.plain("update.console-failed", clre20.aetherMart.util.Vars.of("error", r.error())));
                }
            });
        }
        ready = true;
        activity.log("SYSTEM", "AetherMart " + getPluginMeta().getVersion() + " enabled");
    }

    private void loadConfigs() {
        config.load(this);
        messages.load(this, config.language, config.prefix);
        pdf.load(this, config.language);
        market.load(this);
    }

    /** /mart reload：重新讀取並補齊所有設定檔。 */
    public void reload() {
        loadConfigs();
        activity.configure(config.zone, config.logMaxFileBytes, config.logRetentionDays, config.logMaxTotalBytes);
        currencies.load(config);
        if (!config.language.equals(loadedLanguage)) {
            loadedLanguage = config.language;
            names.load(config.language, worker);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                shop.refreshNames();
                auctions.refreshSearchText();
            }, 100L);
        }
        bot.apply(config.token);
        currencies.checkAll();
    }

    private void tickMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder(false);
            if (h instanceof Tickable t) {
                try {
                    t.tick();
                } catch (RuntimeException e) {
                    getLogger().log(Level.WARNING, "Menu refresh failed", e);
                }
            }
        }
    }

    @Override
    public void onDisable() {
        if (!ready) {
            if (db != null) {
                db.close();
            }
            if (worker != null) {
                worker.shutdownNow();
            }
            return;
        }
        ready = false;
        MenuListener.closeAll();
        input.shutdown();
        auctions.shutdown();
        shop.saveNow();
        notifier.shutdown();
        bot.shutdown();
        links.shutdown();
        updates.shutdown();
        worker.shutdown();
        try {
            worker.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        renderer.close();
        players.clear();
        claims.clear();
        db.close();
        activity.log("SYSTEM", "AetherMart disabled");
        activity.close();
    }

    // ---------------- 服務 ----------------

    public MainConfig config() {
        return config;
    }

    public MarketConfig market() {
        return market;
    }

    public Messages messages() {
        return messages;
    }

    public PdfConfig pdf() {
        return pdf;
    }

    public ExecutorService worker() {
        return worker;
    }

    public ActivityLog activity() {
        return activity;
    }

    public Database db() {
        return db;
    }

    public MetaStore meta() {
        return meta;
    }

    public TradeStore trades() {
        return trades;
    }

    public CurrencyRegistry currencies() {
        return currencies;
    }

    public ItemNames names() {
        return names;
    }

    public PlayerService players() {
        return players;
    }

    public ClaimService claims() {
        return claims;
    }

    public TradeGuard guard() {
        return guard;
    }

    public ShopService shop() {
        return shop;
    }

    public BannedItems banned() {
        return banned;
    }

    public AuctionService auctions() {
        return auctions;
    }

    public ReceiptFactory receipts() {
        return receipts;
    }

    public PdfRenderer renderer() {
        return renderer;
    }

    public DiscordBot bot() {
        return bot;
    }

    public LinkReader links() {
        return links;
    }

    public Notifier notifier() {
        return notifier;
    }

    public ChatInput input() {
        return input;
    }

    public Icons icons() {
        return icons;
    }

    public Sounds sounds() {
        return sounds;
    }

    public UpdateChecker updates() {
        return updates;
    }
}
