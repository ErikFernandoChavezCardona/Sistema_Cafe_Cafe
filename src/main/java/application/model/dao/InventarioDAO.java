package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.InsumoAdmin;
import application.model.entity.MovimientoInsumo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Administración del inventario: insumos, compras, ajustes e historial.
 * Reglas: cada cambio de stock deja una fila en movimientos_inventario (nada se edita en silencio),
 * y lo que escribe en varias tablas va en UNA transacción. (InsumoDAO sigue siendo el de las recetas.)
 */
public class InventarioDAO {

    private static final String BASE = "SELECT i.id_insumo, i.nombre, i.unidad_base, i.stock, i.stock_minimo, "
            + "i.costo_cent_x1000, COALESCE(i.id_proveedor, 0), COALESCE(p.nombre, ''), i.activo, "
            + "(SELECT COUNT(DISTINCT r.id_producto) FROM recetas r JOIN productos pr ON pr.id_producto = r.id_producto "
            + "  WHERE r.id_insumo = i.id_insumo AND pr.activo = 1), "
            + "(SELECT COUNT(*) FROM movimientos_inventario m WHERE m.id_insumo = i.id_insumo) "
            + "FROM insumos i LEFT JOIN proveedores p ON p.id_proveedor = i.id_proveedor";

    private static InsumoAdmin leer(ResultSet rs) throws SQLException {
        return new InsumoAdmin(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getInt(9) == 1, rs.getInt(10), rs.getInt(11));
    }

    /** Todos los insumos; los desactivados van al final. */
    public List<InsumoAdmin> todos() {
        List<InsumoAdmin> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(BASE + " ORDER BY i.activo DESC, i.nombre")) {
            while (rs.next()) lista.add(leer(rs));
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los insumos", e);
        }
    }

    public InsumoAdmin porId(int id) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(BASE + " WHERE i.id_insumo = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leer(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el insumo", e);
        }
    }

    /** Crea (id = 0) o edita un insumo. El stock NO se toca aquí: solo cambia con compras, ventas y ajustes. */
    public int guardar(int id, String nombre, String unidad, int costoX1000, int stockMinimo, int idProveedor, boolean activo) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM insumos WHERE lower(nombre) = lower(?) AND id_insumo <> ?")) {
                    ps.setString(1, nombre);
                    ps.setInt(2, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new IllegalArgumentException("Ya existe un insumo con ese nombre");
                    }
                }
                int resultado = id;
                if (id > 0) {
                    revisarCambioDeUnidad(c, id, unidad);
                    String sql = "UPDATE insumos SET nombre = ?, unidad_base = ?, costo_cent_x1000 = ?, stock_minimo = ?, "
                            + "id_proveedor = ?, activo = ? WHERE id_insumo = ?";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, nombre);
                        ps.setString(2, unidad);
                        ps.setInt(3, costoX1000);
                        ps.setInt(4, stockMinimo);
                        if (idProveedor > 0) ps.setInt(5, idProveedor); else ps.setNull(5, java.sql.Types.INTEGER);
                        ps.setInt(6, activo ? 1 : 0);
                        ps.setInt(7, id);
                        if (ps.executeUpdate() != 1) throw new IllegalStateException("El insumo ya no existe");
                    }
                } else {
                    String sql = "INSERT INTO insumos (nombre, unidad_base, costo_cent_x1000, stock_minimo, id_proveedor, activo) "
                            + "VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, nombre);
                        ps.setString(2, unidad);
                        ps.setInt(3, costoX1000);
                        ps.setInt(4, stockMinimo);
                        if (idProveedor > 0) ps.setInt(5, idProveedor); else ps.setNull(5, java.sql.Types.INTEGER);
                        ps.setInt(6, activo ? 1 : 0);
                        ps.executeUpdate();
                    }
                    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                        rs.next();
                        resultado = rs.getInt(1);
                    }
                }
                c.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el insumo", e);
        }
    }

    /** La unidad solo se puede cambiar mientras el insumo no tenga historial ni esté en recetas (cambiaría su significado). */
    private void revisarCambioDeUnidad(Connection c, int id, String unidadNueva) throws SQLException {
        String sql = "SELECT unidad_base, (SELECT COUNT(*) FROM movimientos_inventario WHERE id_insumo = ?), "
                + "(SELECT COUNT(*) FROM recetas WHERE id_insumo = ?) FROM insumos WHERE id_insumo = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, id);
            ps.setInt(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("El insumo ya no existe");
                if (!rs.getString(1).equals(unidadNueva) && (rs.getInt(2) > 0 || rs.getInt(3) > 0))
                    throw new IllegalArgumentException("La unidad no se puede cambiar: el insumo ya tiene historial o está en recetas");
            }
        }
    }

    /**
     * Registra una compra: suma stock, deja el movimiento "Compra" y, si se pide, actualiza el costo
     * (costo por 1000 unidades = lo pagado / lo comprado) y/o crea el egreso pagado con efectivo de la caja.
     * La primera escritura es la que comprueba que todo exista (insumo activo, y caja abierta si se paga con ella).
     */
    public void registrarCompra(int idInsumo, int cantidadBase, int totalCent, boolean actualizarCosto, boolean pagadoDesdeCaja) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (pagadoDesdeCaja) {
                    String egreso = "INSERT INTO egresos (id_turno, categoria, descripcion, monto_cent, pagado_desde_caja) "
                            + "SELECT t.id_turno, 'Insumos', 'Compra de ' || i.nombre, ?, 1 FROM turnos_caja t, insumos i "
                            + "WHERE t.estado = 'Abierta' AND i.id_insumo = ? AND i.activo = 1";
                    try (PreparedStatement ps = c.prepareStatement(egreso)) {
                        ps.setInt(1, totalCent);
                        ps.setInt(2, idInsumo);
                        if (ps.executeUpdate() != 1)
                            throw new IllegalStateException("Para pagar con efectivo de la caja primero hay que abrir la caja");
                    }
                }
                String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota) "
                        + "SELECT i.id_insumo, 'Compra', ?, COALESCE(p.nombre, 'Compra') FROM insumos i "
                        + "LEFT JOIN proveedores p ON p.id_proveedor = i.id_proveedor WHERE i.id_insumo = ? AND i.activo = 1";
                try (PreparedStatement ps = c.prepareStatement(mov)) {
                    ps.setInt(1, cantidadBase);
                    ps.setInt(2, idInsumo);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("El insumo no existe o está desactivado");
                }
                int costo = (int) Math.round(totalCent * 1000.0 / cantidadBase);
                String upd = "UPDATE insumos SET stock = stock + ?, "
                        + "costo_cent_x1000 = CASE WHEN ? = 1 THEN ? ELSE costo_cent_x1000 END WHERE id_insumo = ?";
                try (PreparedStatement ps = c.prepareStatement(upd)) {
                    ps.setInt(1, cantidadBase);
                    ps.setInt(2, actualizarCosto ? 1 : 0);
                    ps.setInt(3, costo);
                    ps.setInt(4, idInsumo);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar la compra", e);
        }
    }

    /**
     * Ajuste manual con motivo. modo "conteo": cantidadBase es el stock real contado (el sistema calcula la diferencia).
     * modo "merma": cantidadBase es lo perdido (se resta, sin dejar el stock en negativo).
     */
    public void ajustar(int idInsumo, String modo, int cantidadBase, String motivo) {
        boolean conteo = "conteo".equals(modo);
        if (!conteo && !"merma".equals(modo)) throw new IllegalArgumentException("Tipo de ajuste inválido");
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (conteo) {
                    String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota) "
                            + "SELECT id_insumo, 'Ajuste', ? - stock, ? FROM insumos WHERE id_insumo = ? AND stock <> ?";
                    try (PreparedStatement ps = c.prepareStatement(mov)) {
                        ps.setInt(1, cantidadBase);
                        ps.setString(2, "Conteo: " + motivo);
                        ps.setInt(3, idInsumo);
                        ps.setInt(4, cantidadBase);
                        if (ps.executeUpdate() != 1) throw new IllegalArgumentException("El conteo es igual al stock actual: no hay nada que ajustar");
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE insumos SET stock = ? WHERE id_insumo = ?")) {
                        ps.setInt(1, cantidadBase);
                        ps.setInt(2, idInsumo);
                        ps.executeUpdate();
                    }
                } else {
                    String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota) "
                            + "SELECT id_insumo, 'Ajuste', -?, ? FROM insumos WHERE id_insumo = ? AND stock - ? >= 0";
                    try (PreparedStatement ps = c.prepareStatement(mov)) {
                        ps.setInt(1, cantidadBase);
                        ps.setString(2, "Merma: " + motivo);
                        ps.setInt(3, idInsumo);
                        ps.setInt(4, cantidadBase);
                        if (ps.executeUpdate() != 1) throw new IllegalArgumentException("La merma es mayor que el stock actual");
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE insumos SET stock = stock - ? WHERE id_insumo = ?")) {
                        ps.setInt(1, cantidadBase);
                        ps.setInt(2, idInsumo);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el ajuste", e);
        }
    }

    /** Historial de un insumo, lo más reciente primero. */
    public List<MovimientoInsumo> movimientos(int idInsumo, int limite) {
        String sql = "SELECT creado_en, tipo, cantidad, "
                + "CASE WHEN id_venta IS NOT NULL THEN 'Venta #' || id_venta ELSE COALESCE(nota, '') END "
                + "FROM movimientos_inventario WHERE id_insumo = ? ORDER BY id_movimiento DESC LIMIT ?";
        List<MovimientoInsumo> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idInsumo);
            ps.setInt(2, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(new MovimientoInsumo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el historial", e);
        }
    }
}
