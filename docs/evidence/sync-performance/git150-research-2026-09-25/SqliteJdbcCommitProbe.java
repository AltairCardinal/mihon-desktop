import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

/** Synthetic Xerial JDBC probe with a fresh connection per transaction, like JdbcSqliteDriver. */
class SqliteJdbcCommitProbe {
    static String pragma(Connection connection, String name) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            if (!result.next()) throw new AssertionError(name + " missing");
            return result.getString(1);
        }
    }

    static void run(Path directory, String mode, int ordinal) throws Exception {
        Path file = directory.resolve("jdbc-" + ordinal + "-" + mode.toLowerCase() + ".db");
        if (Files.exists(file)) throw new AssertionError("output exists: " + file);
        Properties properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        properties.setProperty("journal_mode", mode);
        properties.setProperty("synchronous", "FULL");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, properties);
             Statement statement = connection.createStatement()) {
            String actualJournal = pragma(connection, "journal_mode");
            String actualSync = pragma(connection, "synchronous");
            if (!mode.equalsIgnoreCase(actualJournal) || !"2".equals(actualSync)) {
                throw new AssertionError("PRAGMA mismatch " + actualJournal + "/" + actualSync);
            }
            statement.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, body BLOB NOT NULL)");
        }
        byte[] body = new byte[256];
        Arrays.fill(body, (byte) 'x');
        double[] commits = new double[80];
        double[] opens = new double[80];
        double[] closes = new double[80];
        long start = System.nanoTime();
        for (int i = 0; i < commits.length; i++) {
            long openStart = System.nanoTime();
            Connection connection = DriverManager.getConnection(url, properties);
            long opened = System.nanoTime();
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO events(body) VALUES (?)")) {
                for (int row = 0; row < 50; row++) {
                    insert.setBytes(1, body);
                    insert.executeUpdate();
                }
            }
            long commitStart = System.nanoTime();
            connection.commit();
            long committed = System.nanoTime();
            connection.close();
            long closed = System.nanoTime();
            opens[i] = (opened - openStart) / 1e6;
            commits[i] = (committed - commitStart) / 1e6;
            closes[i] = (closed - committed) / 1e6;
        }
        double wall = (System.nanoTime() - start) / 1e6;
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            if (!mode.equalsIgnoreCase(pragma(connection, "journal_mode")) ||
                !"2".equals(pragma(connection, "synchronous"))) {
                throw new AssertionError("PRAGMA changed after reconnect");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet count = statement.executeQuery("SELECT count(*) FROM events")) {
                if (!count.next() || count.getInt(1) != 4000) throw new AssertionError("row count");
            }
        }
        Arrays.sort(commits);
        Arrays.sort(opens);
        Arrays.sort(closes);
        System.out.printf(
            "%d %s/FULL tx=80 rows=4000 wallMs=%.3f openMedianMs=%.3f commitMedianMs=%.3f commitP95Ms=%.3f closeMedianMs=%.3f%n",
            ordinal, mode, wall, opens[40], commits[40], commits[75], closes[40]
        );
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        run(directory, "DELETE", 1);
        run(directory, "WAL", 2);
        run(directory, "WAL", 3);
        run(directory, "DELETE", 4);
    }
}
