package clre20.aetherMart.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQLite：單一資料庫執行緒、單一連線，所有讀寫依序執行。主執行緒不碰資料庫。 */
public final class Database {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAction {
        void run(Connection c) throws SQLException;
    }

    private final File file;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AetherMart-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void open() throws Exception {
        call(c -> null, 30);
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            Schema.create(connection);
        }
        return connection;
    }

    public <T> CompletableFuture<T> submit(SqlFunction<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    f.complete(fn.apply(conn()));
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Database error", t);
                    f.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    public void execute(SqlAction action) {
        submit(c -> {
            action.run(c);
            return null;
        });
    }

    /** 交易：全部成功才寫入。 */
    public CompletableFuture<Void> transaction(SqlAction action) {
        return submit(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                action.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    /** 等待結果（只在啟動與關閉時使用）。 */
    public <T> T call(SqlFunction<T> fn, int timeoutSeconds) throws Exception {
        try {
            return submit(fn).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /** 等待佇列中的寫入完成後關閉。 */
    public void close() {
        try {
            submit(c -> {
                c.close();
                return null;
            }).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.WARNING, "Database did not close cleanly", e);
        }
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
