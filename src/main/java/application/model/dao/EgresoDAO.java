package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.EgresoFila;
import application.model.entity.FiltroEgresos;
import application.model.entity.MontoCategoria;
import application.model.entity.ResumenEgresos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class EgresoDAO {

    /** Categorías válidas para egresos nuevos: una lista fija evita variantes como "servicio" y "Servicios". */
    public static final List<String> CATEGORIAS = List.of("General", "Insumos", "Servicios", "Transporte", "Limpieza", "Otros");

    /** Máximo de filas que se traen de una vez: si hay más, la pantalla pide acotar el período. */
    public static final int LIMITE = 1000;

    // ---------- Registrar y anular ----------

    /**
     * Registra un egreso. Si sale del efectivo de la caja (pagadoDesdeCaja) pertenece al turno abierto y se descuenta
     * del efectivo esperado; si no, queda sin turno y no afecta ningún cierre.
     * Para el caso de caja la comprobación "turno abierto" va dentro del mismo INSERT: si el turno se cerró
     * un instante antes, no se inserta nada y no se cuela un gasto en un reporte ya impreso.
     */
    public void registrar(Integer idTurno, String categoria, String descripcion, int montoCent,
                          boolean pagadoDesdeCaja, int idUsuario) {
        String sql;
        if (pagadoDesdeCaja) {
            if (idTurno == null) throw new IllegalStateException("Para pagar con efectivo de la caja primero hay que abrir la caja");
            sql = "INSERT INTO egresos (id_turno, categoria, descripcion, monto_cent, pagado_desde_caja, id_usuario) "
                    + "SELECT id_turno, ?, ?, ?, 1, ? FROM turnos_caja WHERE id_turno = ? AND estado = 'Abierta'";
        } else {
            sql = "INSERT INTO egresos (categoria, descripcion, monto_cent, pagado_desde_caja, id_usuario) VALUES (?, ?, ?, 0, ?)";
        }
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, categoria);
            ps.setString(2, descripcion);
            ps.setInt(3, montoCent);
            ps.setInt(4, idUsuario);
            if (pagadoDesdeCaja) ps.setInt(5, idTurno);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("La caja ya está cerrada");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar el egreso", e);
        }
    }

    /**
     * Anula un egreso (no lo borra). Se puede si está vigente y es de un turno abierto o no pertenece a ningún turno
     * (pagado fuera de la caja). El filtro va dentro del UPDATE para que no se pueda tocar un turno ya cerrado
     * ni anular dos veces lo mismo.
     * Si revertirInventario es true y el egreso viene de una compra de insumos, esa compra se resta del stock
     * (InventarioDAO.revertirCompra) en la MISMA transacción: o se anula y se revierte todo, o no pasa nada.
     * Devuelve true si además se restauró el costo del insumo.
     */
    public boolean anular(int idEgreso, String motivo, int idUsuario, boolean revertirInventario) {
        String sql = "UPDATE egresos SET anulado = 1, motivo_anulacion = ?, id_usuario_anulo = ?, "
                + "anulado_en = datetime('now','localtime') WHERE id_egreso = ? AND anulado = 0 "
                + "AND (id_turno IS NULL OR id_turno IN (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta'))";
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, motivo);
                    ps.setInt(2, idUsuario);
                    ps.setInt(3, idEgreso);
                    if (ps.executeUpdate() != 1) {
                        throw new IllegalStateException("No se pudo anular: ya está anulado o es de un turno cerrado");
                    }
                }
                boolean costoRestaurado = revertirInventario && InventarioDAO.revertirCompra(c, idEgreso, motivo, idUsuario);
                c.commit();
                return costoRestaurado;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo anular el egreso", e);
        }
    }

    // ---------- Consultas de la pantalla Egresos ----------

    private static final String SQL_FILAS =
            "SELECT e.id_egreso, e.creado_en, e.descripcion, e.categoria, e.monto_cent, e.pagado_desde_caja, "
                    + "e.id_turno, t.estado, COALESCE(u.nombre || ' (' || u.usuario || ')', ''), e.anulado, "
                    + "COALESCE(e.motivo_anulacion, ''), COALESCE(ua.nombre || ' (' || ua.usuario || ')', ''), "
                    + "CASE WHEN e.id_movimiento_inventario IS NOT NULL AND e.anulado = 0 THEN 1 ELSE 0 END "
                    + "FROM egresos e LEFT JOIN turnos_caja t ON t.id_turno = e.id_turno "
                    + "LEFT JOIN usuarios u ON u.id_usuario = e.id_usuario "
                    + "LEFT JOIN usuarios ua ON ua.id_usuario = e.id_usuario_anulo ";
    private static final String SQL_TOTALES =
            "SELECT COALESCE(SUM(CASE WHEN e.anulado = 0 THEN e.monto_cent ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN e.anulado = 0 AND e.pagado_desde_caja = 1 THEN e.monto_cent ELSE 0 END), 0), "
                    + "COALESCE(SUM(CASE WHEN e.anulado = 0 AND e.pagado_desde_caja = 0 THEN e.monto_cent ELSE 0 END), 0), "
                    + "COALESCE(SUM(e.anulado), 0) FROM egresos e ";
    private static final String SQL_POR_CATEGORIA = "SELECT e.categoria, SUM(e.monto_cent) FROM egresos e ";
    private static final String W_FECHA = "WHERE date(e.creado_en) BETWEEN ? AND ? ";
    private static final String W_CATEGORIA = "AND e.categoria = ? ";
    private static final String W_ORIGEN = "AND e.pagado_desde_caja = ? ";
    private static final String W_ESTADO = "AND e.anulado = ? ";
    private static final String W_TEXTO = "AND e.descripcion LIKE ? ESCAPE '\\' ";

    /** Arma el WHERE con solo los filtros usados y deja los valores en params (siempre con "?", nunca pegados al texto). */
    private static String donde(FiltroEgresos f, List<Object> params) {
        StringBuilder w = new StringBuilder(W_FECHA);
        params.add(f.desde());
        params.add(f.hasta());
        if (!f.categoria().isEmpty()) {
            w.append(W_CATEGORIA);
            params.add(f.categoria());
        }
        if (f.origen() == 1 || f.origen() == 2) {
            w.append(W_ORIGEN);
            params.add(f.origen() == 1 ? 1 : 0);
        }
        if (f.estado() == 1 || f.estado() == 2) {
            w.append(W_ESTADO);
            params.add(f.estado() == 1 ? 0 : 1);
        }
        if (!f.texto().isEmpty()) {
            w.append(W_TEXTO);
            // % y _ del texto buscado se tratan como letras normales
            params.add("%" + f.texto().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return w.toString();
    }

    private static void poner(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer n) ps.setInt(i + 1, n); else ps.setString(i + 1, (String) p);
        }
    }

    /** Egresos que cumplen los filtros, lo más reciente primero (hasta LIMITE filas). */
    public List<EgresoFila> listar(FiltroEgresos f) {
        List<Object> params = new ArrayList<>();
        String sql = SQL_FILAS + donde(f, params) + "ORDER BY e.creado_en DESC, e.id_egreso DESC LIMIT " + LIMITE;
        List<EgresoFila> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            poner(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int turno = rs.getInt(7);
                    Integer idTurno = rs.wasNull() ? null : turno;
                    lista.add(new EgresoFila(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            rs.getInt(6) == 1, idTurno, rs.getString(8), rs.getString(9), rs.getInt(10) == 1,
                            rs.getString(11), rs.getString(12), rs.getInt(13) == 1));
                }
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los egresos", e);
        }
    }

    /** Totales y gasto por categoría con los MISMOS filtros que la lista, para que las tarjetas siempre cuadren con la tabla. */
    public ResumenEgresos resumen(FiltroEgresos f) {
        List<Object> params = new ArrayList<>();
        String donde = donde(f, params);
        try (Connection c = DatabaseConnection.getConnection()) {
            int total, caja, fuera, anulados;
            try (PreparedStatement ps = c.prepareStatement(SQL_TOTALES + donde)) {
                poner(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                    caja = rs.getInt(2);
                    fuera = rs.getInt(3);
                    anulados = rs.getInt(4);
                }
            }
            List<MontoCategoria> cats = new ArrayList<>();
            String porCat = SQL_POR_CATEGORIA + donde + "AND e.anulado = 0 GROUP BY e.categoria ORDER BY SUM(e.monto_cent) DESC";
            try (PreparedStatement ps = c.prepareStatement(porCat)) {
                poner(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cats.add(new MontoCategoria(rs.getString(1), rs.getInt(2)));
                }
            }
            return new ResumenEgresos(total, caja, fuera, anulados, cats);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo calcular el resumen de egresos", e);
        }
    }

    /** Categorías que ya existen en la base y no están en la lista fija (egresos viejos), para poder filtrarlas. */
    public List<String> categoriasViejas() {
        List<String> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DISTINCT categoria FROM egresos ORDER BY categoria")) {
            while (rs.next()) {
                if (!CATEGORIAS.contains(rs.getString(1))) lista.add(rs.getString(1));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las categorías", e);
        }
    }
}
