package application.model.connection;

import application.util.AppPaths;
import org.sqlite.SQLiteConfig;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Conexión a SQLite. La base es un archivo (cafecafe.db) en la carpeta de datos
 * del usuario.
 * El esquema se crea y se actualiza solo con migraciones numeradas (V1, V2...).
 */
public final class DatabaseConnection {

    /**
     * Para cambiar la base en el futuro: agrega un archivo V2__... aquí. NUNCA
     * edites uno ya entregado.
     */
    private static final String[] MIGRACIONES = {
            "/application/db/V1__esquema_inicial.sql",
            "/application/db/V2__menu_inicial.sql",
            "/application/db/V3__usuario_cierre_turno.sql"
    };

    private DatabaseConnection() {
    }

    private static SQLiteConfig config() {
        SQLiteConfig c = new SQLiteConfig();
        c.enforceForeignKeys(true); // sin esto SQLite ignora las llaves foráneas
        c.setJournalMode(SQLiteConfig.JournalMode.WAL); // resiste mejor los cortes de luz
        c.setSynchronous(SQLiteConfig.SynchronousMode.FULL); // cada venta se escribe de verdad en disco
        c.setBusyTimeout(5000);
        return c;
    }

    public static Connection getConnection() throws SQLException {
        return config().createConnection("jdbc:sqlite:" + AppPaths.dbFile().toAbsolutePath());
    }

    /** Se llama una vez al arrancar la app. */
    public static void initialize() {
        try (Connection c = getConnection()) {
            int actual = versionActual(c);
            for (int i = actual; i < MIGRACIONES.length; i++) {
                aplicar(c, MIGRACIONES[i], i + 1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo iniciar la base de datos", e);
        }
    }

    private static int versionActual(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void aplicar(Connection c, String recurso, int version) throws Exception {
        List<String> sentencias = dividir(leer(recurso));
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            for (String sql : sentencias) {
                s.execute(sql);
            }
            s.execute("PRAGMA user_version = " + version);
            c.commit(); // o se aplica toda la migración o nada
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String leer(String recurso) throws Exception {
        try (InputStream in = DatabaseConnection.class.getResourceAsStream(recurso)) {
            if (in == null)
                throw new IllegalStateException("Falta la migración: " + recurso);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Separa por ';' ignorando comentarios (--). Por eso las migraciones no deben
     * usar triggers.
     */
    private static List<String> dividir(String script) {
        StringBuilder limpio = new StringBuilder();
        for (String linea : script.split("\\R")) {
            limpio.append(linea.replaceAll("--.*$", "")).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String parte : limpio.toString().split(";")) {
            if (!parte.isBlank())
                out.add(parte.trim());
        }
        return out;
    }
}
