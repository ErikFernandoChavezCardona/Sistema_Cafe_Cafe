package application.model.dao;

import application.model.connection.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas de los reportes. SOLO LEEN: no modifican nada. Todo el dinero va en centavos.
 * Las fechas del período llegan como LocalDate y se usan como [desde 00:00, hasta + 1 día 00:00).
 *
 * Reglas de cálculo:
 * - Ventas: solo las Completadas, por la fecha de cobro (cobrada_en). Las pendientes y canceladas no cuentan.
 * - Egresos: los vigentes (no anulados), por la fecha en que se registraron.
 * - Utilidad estimada = ventas - egresos. NO se resta además el costo de los insumos, porque las compras ya entran como
 *   egresos y se contarían dos veces. Es un flujo de dinero, no una contabilidad formal.
 * - Margen por producto: lo vendido menos el costo de la receta con el costo ACTUAL de cada insumo. Sin receta, o con algún
 *   insumo sin costo, queda sin margen (null) para no mostrar un margen falso.
 * - Libro de ventas: solo facturas vigentes suman; las anuladas se cuentan aparte.
 * - Turnos: lo esperado, contado y la diferencia son los que se guardaron al cerrar la caja.
 */
public final class ReporteDAO {

    /** Un día del período (con ceros si no hubo ventas ni egresos). */
    public record Dia(String fecha, int ventas, long total, long efectivo, long tarjeta, long transferencia, long egresos) {
    }

    /** Un bloque de 2 horas (desde = hora en que empieza, 0 a 22) y lo vendido en él. */
    public record Hora(int desde, long total) {
    }

    public record TopProducto(String nombre, long cantidad, long monto) {
    }

    /** Insumo bajo el mínimo: stock y mínimo en la unidad base (g, ml o ud). */
    public record InsumoBajo(String nombre, String unidad, long stock, long minimo) {
    }

    /** costoCent = costo estimado de lo vendido (null si el producto no tiene receta o le falta algún costo). */
    public record FilaProducto(String nombre, String categoria, long cantidad, long montoCent, Long costoCent) {
    }

    /** Si el turno sigue abierto, esperado, contado y diferencia vienen null. */
    public record FilaTurno(int id, String apertura, String cajero, String cierre, Long esperadoCent, Long contadoCent,
                            Long diferenciaCent, String estado) {
    }

    public record FilaLibro(String fecha, String desde, String hasta, int emitidas, int anuladas, long gravadoCent,
                            long isvCent, long totalCent) {
    }

    public record Resumen(List<Dia> dias, List<Hora> horas, List<TopProducto> top, List<InsumoBajo> bajos,
                          int turnosCerrados, int turnosConDiferencia, long diferenciaCent, int facturasAnuladas) {
    }

    // ---- SQL ----
    // Fecha de cobro de una venta: si por algún motivo faltara cobrada_en, se usa la de creación
    private static final String FECHA_VENTA = "COALESCE(v.cobrada_en, v.creada_en)";
    private static final String VENTA_EN_PERIODO = "v.estado = 'Completada' AND " + FECHA_VENTA + " >= ? AND " + FECHA_VENTA + " < ?";

    private static final String SQL_VENTAS_DIA = "SELECT date(" + FECHA_VENTA + "), COUNT(*), SUM(v.total_cent), "
            + "SUM(CASE WHEN v.metodo_pago = 'Efectivo' THEN v.total_cent ELSE 0 END), "
            + "SUM(CASE WHEN v.metodo_pago = 'Tarjeta' THEN v.total_cent ELSE 0 END), "
            + "SUM(CASE WHEN v.metodo_pago = 'Transferencia' THEN v.total_cent ELSE 0 END) "
            + "FROM ventas v WHERE " + VENTA_EN_PERIODO + " GROUP BY 1";

    private static final String SQL_EGRESOS_DIA = "SELECT date(e.creado_en), SUM(e.monto_cent) FROM egresos e "
            + "WHERE e.anulado = 0 AND e.creado_en >= ? AND e.creado_en < ? GROUP BY 1";

    // Bloques de 2 horas (0-2, 2-4 ... 22-24): los 6 con más ventas
    private static final String SQL_HORAS = "SELECT (CAST(strftime('%H', " + FECHA_VENTA + ") AS INTEGER) / 2) * 2, SUM(v.total_cent) "
            + "FROM ventas v WHERE " + VENTA_EN_PERIODO + " GROUP BY 1 ORDER BY SUM(v.total_cent) DESC LIMIT 6";

    private static final String DETALLE = "FROM detalle_ventas d JOIN ventas v ON v.id_venta = d.id_venta "
            + "JOIN productos p ON p.id_producto = d.id_producto JOIN categorias c ON c.id_categoria = p.id_categoria "
            + "WHERE " + VENTA_EN_PERIODO + " GROUP BY p.id_producto ";

    private static final String SQL_TOP = "SELECT p.nombre, SUM(d.cantidad), SUM(d.subtotal_cent) " + DETALLE
            + "ORDER BY SUM(d.cantidad) DESC, SUM(d.subtotal_cent) DESC LIMIT 5";

    // costo_x1000: lo que cuesta la receta de UNA unidad, en centavos por 1000 (cantidad de la receta x costo del insumo)
    // sin_costo: cuántos insumos de la receta no tienen costo guardado
    private static final String SQL_PRODUCTOS = "SELECT p.nombre, c.nombre, SUM(d.cantidad), SUM(d.subtotal_cent), "
            + "(SELECT SUM(r.cantidad * i.costo_cent_x1000) FROM recetas r JOIN insumos i ON i.id_insumo = r.id_insumo WHERE r.id_producto = p.id_producto), "
            + "(SELECT COUNT(*) FROM recetas r JOIN insumos i ON i.id_insumo = r.id_insumo WHERE r.id_producto = p.id_producto AND i.costo_cent_x1000 = 0) "
            + DETALLE + "ORDER BY SUM(d.subtotal_cent) DESC";

    // Mismo criterio que la pantalla Insumos: agotado (0 o menos) o por debajo de su mínimo
    private static final String SQL_BAJOS = "SELECT nombre, unidad_base, stock, stock_minimo FROM insumos "
            + "WHERE activo = 1 AND (stock <= 0 OR stock < stock_minimo) ORDER BY stock ASC, nombre LIMIT 8";

    private static final String SQL_TURNOS = "SELECT t.id_turno, t.apertura_en, u.nombre, t.cierre_en, t.monto_esperado_cent, "
            + "t.monto_contado_cent, t.diferencia_cent, t.estado FROM turnos_caja t JOIN usuarios u ON u.id_usuario = t.id_usuario "
            + "WHERE t.apertura_en >= ? AND t.apertura_en < ? ORDER BY t.apertura_en DESC";

    // El rango de números incluye las anuladas: también gastaron un número del CAI
    private static final String SQL_LIBRO = "SELECT date(emitida_en), MIN(numero), MAX(numero), "
            + "SUM(CASE WHEN anulada = 0 THEN 1 ELSE 0 END), SUM(CASE WHEN anulada = 1 THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN anulada = 0 THEN subtotal_cent ELSE 0 END), SUM(CASE WHEN anulada = 0 THEN impuesto_cent ELSE 0 END), "
            + "SUM(CASE WHEN anulada = 0 THEN total_cent ELSE 0 END) "
            + "FROM facturas_venta WHERE emitida_en >= ? AND emitida_en < ? GROUP BY 1 ORDER BY 1 DESC";

    /** Todo lo de la pestaña Resumen en un solo paso. */
    public Resumen resumen(LocalDate desde, LocalDate hasta) {
        String a = desde + " 00:00:00", b = hasta.plusDays(1) + " 00:00:00";
        try (Connection c = DatabaseConnection.getConnection()) {
            Map<String, long[]> por = new LinkedHashMap<>();   // fecha -> {ventas, total, efectivo, tarjeta, transferencia, egresos}
            for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) por.put(d.toString(), new long[6]);

            try (PreparedStatement ps = c.prepareStatement(SQL_VENTAS_DIA)) {
                ps.setString(1, a);
                ps.setString(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long[] x = por.get(rs.getString(1));
                        if (x == null) continue;
                        for (int i = 0; i < 5; i++) x[i] = rs.getLong(i + 2);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(SQL_EGRESOS_DIA)) {
                ps.setString(1, a);
                ps.setString(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long[] x = por.get(rs.getString(1));
                        if (x != null) x[5] = rs.getLong(2);
                    }
                }
            }
            List<Dia> dias = new ArrayList<>();
            por.forEach((f, x) -> dias.add(new Dia(f, (int) x[0], x[1], x[2], x[3], x[4], x[5])));

            List<Hora> horas = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SQL_HORAS)) {
                ps.setString(1, a);
                ps.setString(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) horas.add(new Hora(rs.getInt(1), rs.getLong(2)));
                }
            }
            List<TopProducto> top = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SQL_TOP)) {
                ps.setString(1, a);
                ps.setString(2, b);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) top.add(new TopProducto(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
            List<InsumoBajo> bajos = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SQL_BAJOS);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) bajos.add(new InsumoBajo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
            }

            // Control de caja: turnos cerrados del período y facturas anuladas
            int cerrados = 0, conDif = 0;
            long difTotal = 0;
            for (FilaTurno t : turnos(c, a, b)) {
                if (!"Cerrada".equals(t.estado())) continue;
                cerrados++;
                long d = t.diferenciaCent() == null ? 0 : t.diferenciaCent();
                if (d != 0) conDif++;
                difTotal += d;
            }
            int anuladas = 0;
            for (FilaLibro l : libro(c, a, b)) anuladas += l.anuladas();

            return new Resumen(dias, horas, top, bajos, cerrados, conDif, difTotal, anuladas);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el resumen de ventas", e);
        }
    }

    /** Lo vendido por producto en el período, lo que más vendió primero. */
    public List<FilaProducto> productos(LocalDate desde, LocalDate hasta) {
        List<FilaProducto> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_PRODUCTOS)) {
            ps.setString(1, desde + " 00:00:00");
            ps.setString(2, hasta.plusDays(1) + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cantidad = rs.getLong(3);
                    long costoX1000 = rs.getLong(5);
                    boolean tieneReceta = !rs.wasNull();
                    boolean completo = tieneReceta && rs.getInt(6) == 0;
                    Long costo = completo ? Math.round(cantidad * costoX1000 / 1000.0) : null;
                    lista.add(new FilaProducto(rs.getString(1), rs.getString(2), cantidad, rs.getLong(4), costo));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los productos vendidos", e);
        }
        return lista;
    }

    public List<FilaTurno> turnos(LocalDate desde, LocalDate hasta) {
        try (Connection c = DatabaseConnection.getConnection()) {
            return turnos(c, desde + " 00:00:00", hasta.plusDays(1) + " 00:00:00");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los turnos de caja", e);
        }
    }

    public List<FilaLibro> libro(LocalDate desde, LocalDate hasta) {
        try (Connection c = DatabaseConnection.getConnection()) {
            return libro(c, desde + " 00:00:00", hasta.plusDays(1) + " 00:00:00");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el libro de ventas", e);
        }
    }

    private static List<FilaTurno> turnos(Connection c, String a, String b) throws SQLException {
        List<FilaTurno> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_TURNOS)) {
            ps.setString(1, a);
            ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new FilaTurno(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            largo(rs, 5), largo(rs, 6), largo(rs, 7), rs.getString(8)));
                }
            }
        }
        return lista;
    }

    private static List<FilaLibro> libro(Connection c, String a, String b) throws SQLException {
        List<FilaLibro> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_LIBRO)) {
            ps.setString(1, a);
            ps.setString(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new FilaLibro(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                            rs.getLong(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return lista;
    }

    /** Un número que puede ser NULL en la base (por ejemplo el contado de un turno abierto). */
    private static Long largo(ResultSet rs, int columna) throws SQLException {
        long v = rs.getLong(columna);
        return rs.wasNull() ? null : v;
    }
}
