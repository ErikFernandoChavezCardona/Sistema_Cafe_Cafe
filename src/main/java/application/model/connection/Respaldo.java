package application.model.connection;

import application.model.dao.AuditoriaDAO;
import application.model.entity.Usuario;
import application.util.AppPaths;
import application.util.Registro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copias de seguridad de la base de datos.
 * <ul>
 * <li>Se hacen con VACUUM INTO: copia consistente aunque el programa esté en uso.</li>
 * <li>Cada copia se verifica (integrity_check) antes de darla por buena.</li>
 * <li>Copia local en %APPDATA%\CafeCafe\respaldos y, si se configuró, otra en una carpeta externa (USB, otro disco).</li>
 * <li>Restaurar = dejar la copia como "pendiente" y reemplazar la base al abrir el programa (así nada la tiene abierta).</li>
 * </ul>
 */
public final class Respaldo {

    public record Copia(String nombre, long bytes, String fecha) {
    }

    public record Resultado(boolean ok, String mensaje, boolean externoConfigurado, boolean externoOk) {
    }

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NOMBRE_VALIDO = Pattern.compile("(cafecafe|antes-de-actualizar-v\\d+|antes-de-restaurar)-\\d{8}-\\d{6}(-\\d+)?\\.db");
    private static final int GUARDAR_AUTOMATICAS = 40;
    private static final int GUARDAR_DE_SEGURIDAD = 5;

    private static boolean restauracionAplicada;

    private Respaldo() {
    }

    // ---------- Hacer copias ----------

    /** Copia ahora mismo (local y, si hay, externa). 'quien' puede ser null cuando la hace el programa solo. */
    public static synchronized Resultado hacerCopia(String motivo, Usuario quien) {
        boolean externoConfigurado = false;
        boolean externoOk = false;
        try {
            Path destino = nombreLibre(AppPaths.respaldosDir(), "cafecafe-");
            try (Connection c = DatabaseConnection.getConnection()) {
                copiarCon(c, destino);
            }
            verificar(destino);
            ponerConfig("respaldo_ultimo_local", ahora());
            rotar("cafecafe-", GUARDAR_AUTOMATICAS);

            String externa = carpetaExterna();
            String aviso = "";
            if (!externa.isBlank()) {
                externoConfigurado = true;
                try {
                    copiarAExterna(destino, Path.of(externa));
                    externoOk = true;
                    ponerConfig("respaldo_ultimo_externo", ahora());
                } catch (Exception e) {
                    Registro.error("No se pudo copiar el respaldo a la carpeta externa", e);
                    aviso = " Pero NO se pudo copiar a la carpeta externa (¿está conectada la USB?).";
                }
            }
            AuditoriaDAO.registrar(quien, "Respaldo", motivo + " · " + destino.getFileName()
                    + (externoConfigurado ? (externoOk ? " · copia externa OK" : " · copia externa FALLÓ") : ""));
            return new Resultado(true, "Copia creada: " + destino.getFileName() + "." + aviso, externoConfigurado, externoOk);
        } catch (Exception e) {
            Registro.error("Falló el respaldo (" + motivo + ")", e);
            AuditoriaDAO.registrar(quien, "Respaldo fallido", motivo);
            return new Resultado(false, "No se pudo crear la copia de seguridad", externoConfigurado, false);
        }
    }

    /** Hace una copia solo si la última es más vieja que 'horas' (o no hay ninguna). Nunca lanza excepciones. */
    public static void automaticaSiHaceFalta(int horas, String motivo) {
        try {
            String ultima = ultimaLocal();
            if (!ultima.isBlank()) {
                LocalDateTime t = LocalDateTime.parse(ultima, FECHA);
                if (t.plusHours(horas).isAfter(LocalDateTime.now())) return;
            }
            hacerCopia(motivo, null);
        } catch (Exception e) {
            Registro.error("Falló el respaldo automático", e);
        }
    }

    /** Antes de actualizar la estructura de la base (migraciones): una copia de seguridad por si algo sale mal. */
    static void copiaAntesDeActualizar(Connection c, int versionActual) {
        try {
            Path destino = nombreLibre(AppPaths.respaldosDir(), "antes-de-actualizar-v" + versionActual + "-");
            copiarCon(c, destino);
            rotar("antes-de-actualizar-", GUARDAR_DE_SEGURIDAD);
        } catch (Exception e) {
            // No se bloquea el arranque por esto, pero queda anotado
            Registro.error("No se pudo hacer la copia previa a la actualización", e);
        }
    }

    // ---------- Consultas ----------

    public static List<Copia> locales() {
        List<Copia> lista = new ArrayList<>();
        try (Stream<Path> s = Files.list(AppPaths.respaldosDir())) {
            s.filter(p -> NOMBRE_VALIDO.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingLong(Respaldo::modificado).reversed())
                    .forEach(p -> lista.add(new Copia(p.getFileName().toString(), tamano(p),
                            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(modificado(p)),
                                    java.time.ZoneId.systemDefault()).format(FECHA))));
        } catch (IOException e) {
            Registro.error("No se pudo listar los respaldos", e);
        }
        return lista;
    }

    public static String ultimaLocal() {
        return leerConfig("respaldo_ultimo_local");
    }

    public static String ultimaExterna() {
        return leerConfig("respaldo_ultimo_externo");
    }

    public static String carpetaExterna() {
        return leerConfig("respaldo_externo");
    }

    /** Guarda la carpeta externa. Vacía = desactivar. Devuelve el motivo si no sirve, o null si quedó bien. */
    public static String fijarCarpetaExterna(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            ponerConfig("respaldo_externo", "");
            return null;
        }
        try {
            Path p = Path.of(ruta.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) return "Esa carpeta no existe";
            if (p.startsWith(AppPaths.dataDir().toAbsolutePath().normalize()))
                return "Elige una carpeta fuera de la del programa (mejor una USB u otro disco)";
            Path prueba = Files.createTempFile(p, "prueba-", ".tmp");
            Files.deleteIfExists(prueba);
            ponerConfig("respaldo_externo", p.toString());
            return null;
        } catch (Exception e) {
            return "No se puede escribir en esa carpeta";
        }
    }

    // ---------- Restaurar ----------

    /** Prepara la restauración de un archivo de respaldo; se aplica al volver a abrir el programa. */
    public static String solicitarRestauracion(Path origen) {
        try {
            if (origen == null || !Files.isRegularFile(origen)) return "No se encontró el archivo";
            int version = verificar(origen);
            if (version > DatabaseConnection.versionMaxima())
                return "Esa copia es de una versión más nueva del programa";
            Files.copy(origen, AppPaths.restauracionPendiente(), StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (Exception e) {
            Registro.error("Restauración rechazada", e);
            return "El archivo no es una copia válida de este sistema";
        }
    }

    /** Solo el nombre de una copia local (no una ruta), para que nadie pueda salirse de la carpeta de respaldos. */
    public static Path rutaLocal(String nombre) {
        if (nombre == null || !NOMBRE_VALIDO.matcher(nombre).matches()) return null;
        Path p = AppPaths.respaldosDir().resolve(nombre);
        return Files.isRegularFile(p) ? p : null;
    }

    /** Se llama al arrancar, ANTES de abrir la base. Reemplaza la base por la copia pendiente. */
    static void aplicarRestauracionPendiente() {
        Path pendiente = AppPaths.restauracionPendiente();
        if (!Files.exists(pendiente)) return;
        try {
            verificar(pendiente);
            Path actual = AppPaths.dbFile();
            if (Files.exists(actual)) {
                try {
                    Path seguridad = nombreLibre(AppPaths.respaldosDir(), "antes-de-restaurar-");
                    try (Connection c = DatabaseConnection.getConnection()) {
                        copiarCon(c, seguridad);
                    }
                    rotar("antes-de-restaurar-", GUARDAR_DE_SEGURIDAD);
                } catch (Exception e) {
                    Registro.error("No se pudo guardar la base actual antes de restaurar", e);
                    return; // sin la copia de seguridad de lo actual, mejor no tocar nada
                }
            }
            Files.deleteIfExists(Path.of(actual + "-wal"));
            Files.deleteIfExists(Path.of(actual + "-shm"));
            Files.move(pendiente, actual, StandardCopyOption.REPLACE_EXISTING);
            restauracionAplicada = true;
            Registro.info("Base de datos restaurada desde una copia de seguridad");
        } catch (Exception e) {
            Registro.error("No se pudo aplicar la restauración pendiente", e);
            try {
                Files.deleteIfExists(pendiente); // copia dañada: se descarta para no intentar siempre
            } catch (IOException ignorar) {
                // nada más que hacer
            }
        }
    }

    static boolean restauracionAplicada() {
        return restauracionAplicada;
    }

    // ---------- Internos ----------

    private static void copiarCon(Connection c, Path destino) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, destino.toAbsolutePath().toString());
            ps.execute();
        }
    }

    /** Abre la copia y comprueba que no esté dañada. Devuelve su versión de esquema (user_version). */
    private static int verificar(Path archivo) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + archivo.toAbsolutePath());
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1)))
                    throw new SQLException("La copia está dañada: " + archivo.getFileName());
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'usuarios'")) {
                if (!rs.next() || rs.getInt(1) != 1) throw new SQLException("No es una base de Café Café");
            }
            try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void copiarAExterna(Path origen, Path carpeta) throws IOException {
        if (!Files.isDirectory(carpeta)) throw new IOException("La carpeta externa no está disponible");
        Path temporal = carpeta.resolve(origen.getFileName() + ".parcial");
        Path definitivo = carpeta.resolve(origen.getFileName());
        Files.copy(origen, temporal, StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(temporal) != Files.size(origen)) {
            Files.deleteIfExists(temporal);
            throw new IOException("La copia externa quedó incompleta");
        }
        Files.move(temporal, definitivo, StandardCopyOption.REPLACE_EXISTING);
        rotarCarpeta(carpeta, "cafecafe-", GUARDAR_AUTOMATICAS);
    }

    private static Path nombreLibre(Path carpeta, String prefijo) {
        String sello = LocalDateTime.now().format(SELLO);
        Path p = carpeta.resolve(prefijo + sello + ".db");
        for (int n = 2; Files.exists(p); n++) p = carpeta.resolve(prefijo + sello + "-" + n + ".db");
        return p;
    }

    private static void rotar(String prefijo, int guardar) {
        rotarCarpeta(AppPaths.respaldosDir(), prefijo, guardar);
    }

    private static void rotarCarpeta(Path carpeta, String prefijo, int guardar) {
        try (Stream<Path> s = Files.list(carpeta)) {
            List<Path> viejos = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefijo) && NOMBRE_VALIDO.matcher(n).matches();
                    })
                    .sorted(Comparator.comparingLong(Respaldo::modificado).reversed())
                    .skip(guardar).toList();
            for (Path p : viejos) Files.deleteIfExists(p);
        } catch (Exception e) {
            Registro.error("No se pudieron limpiar respaldos viejos", e);
        }
    }

    private static long modificado(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long tamano(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String ahora() {
        return LocalDateTime.now().format(FECHA);
    }

    private static String leerConfig(String clave) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT valor FROM config WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            Registro.error("No se pudo leer la configuración " + clave, e);
            return "";
        }
    }

    private static void ponerConfig(String clave, String valor) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO config (clave, valor) VALUES (?, ?)")) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.executeUpdate();
        } catch (SQLException e) {
            Registro.error("No se pudo guardar la configuración " + clave, e);
        }
    }
}
