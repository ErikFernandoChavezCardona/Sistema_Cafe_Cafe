package application.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * Registro de errores en un archivo (%APPDATA%\CafeCafe\logs\cafecafe-AAAA-MM-DD.log).
 * Nunca lanza excepciones: registrar un error jamás debe causar otro error.
 */
public final class Registro {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DIAS_A_GUARDAR = 30;

    private Registro() {
    }

    public static void info(String mensaje) {
        escribir("INFO ", mensaje, null);
    }

    public static void error(Throwable e) {
        escribir("ERROR", e == null ? "(sin detalle)" : String.valueOf(e.getMessage()), e);
    }

    public static void error(String contexto, Throwable e) {
        escribir("ERROR", contexto, e);
    }

    /** Borra los registros de más de 30 días. */
    public static void limpiarViejos() {
        try (Stream<Path> archivos = Files.list(AppPaths.logsDir())) {
            LocalDate limite = LocalDate.now().minusDays(DIAS_A_GUARDAR);
            archivos.filter(a -> a.getFileName().toString().matches("cafecafe-\\d{4}-\\d{2}-\\d{2}\\.log"))
                    .forEach(a -> {
                        try {
                            String f = a.getFileName().toString();
                            LocalDate d = LocalDate.parse(f.substring(9, 19));
                            if (d.isBefore(limite)) Files.deleteIfExists(a);
                        } catch (Exception ignorar) {
                            // un archivo raro no debe detener la limpieza
                        }
                    });
        } catch (Exception ignorar) {
            // sin logs que limpiar
        }
    }

    private static synchronized void escribir(String nivel, String mensaje, Throwable e) {
        StringBuilder b = new StringBuilder();
        b.append(LocalDateTime.now().format(HORA)).append(' ').append(nivel).append(' ').append(mensaje).append('\n');
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            b.append(sw);
        }
        System.err.print(b); // también a la consola, útil mientras se programa
        try {
            Path archivo = AppPaths.logsDir().resolve("cafecafe-" + LocalDate.now() + ".log");
            Files.writeString(archivo, b.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignorar) {
            // si ni el archivo se puede escribir, ya quedó en consola
        }
    }
}
