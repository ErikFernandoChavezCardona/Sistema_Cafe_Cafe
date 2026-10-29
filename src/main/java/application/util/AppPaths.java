package application.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carpetas de datos del programa. Van en la carpeta del usuario (no dentro del programa instalado),
 * así se pueden escribir y no se pierden al actualizar.  Windows: C:\Users\...\AppData\Roaming\CafeCafe
 */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path dataDir() {
        String base = System.getenv("APPDATA");
        Path dir = (base != null) ? Path.of(base, "CafeCafe") : Path.of(System.getProperty("user.home"), ".cafecafe");
        return crear(dir);
    }

    public static Path dbFile() {
        return dataDir().resolve("cafecafe.db");
    }

    /** Fotos de productos (originales y miniaturas). */
    public static Path imagenesDir() {
        return crear(dataDir().resolve("imagenes"));
    }

    /** Copias de seguridad automáticas de la base de datos. */
    public static Path respaldosDir() {
        return crear(dataDir().resolve("respaldos"));
    }

    /** Registro de errores del programa (un archivo por día). */
    public static Path logsDir() {
        return crear(dataDir().resolve("logs"));
    }

    /** Copia lista para reemplazar la base la próxima vez que abra el programa (restauración). */
    public static Path restauracionPendiente() {
        return dataDir().resolve("restaurar.pendiente.db");
    }

    private static Path crear(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear la carpeta " + dir, e);
        }
    }
}
