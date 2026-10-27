package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.InsumoAdmin;
import application.model.entity.MovimientoInsumo;

import java.math.BigDecimal;
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

    /**
     * Crea (id = 0) o edita un insumo. El stock y el costo NO se tocan aquí: el stock solo cambia con compras,
     * ventas y ajustes, y el costo lo calcula cada compra (lo pagado entre lo comprado). Así nadie puede escribir
     * un costo a mano ni pisar el que se calculó con precisión.
     */
    public int guardar(int id, String nombre, String unidad, int stockMinimo, int idProveedor, boolean activo) {
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
                    String sql = "UPDATE insumos SET nombre = ?, unidad_base = ?, stock_minimo = ?, "
                            + "id_proveedor = ?, activo = ? WHERE id_insumo = ?";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, nombre);
                        ps.setString(2, unidad);
                        ps.setInt(3, stockMinimo);
                        if (idProveedor > 0) ps.setInt(4, idProveedor); else ps.setNull(4, java.sql.Types.INTEGER);
                        ps.setInt(5, activo ? 1 : 0);
                        ps.setInt(6, id);
                        if (ps.executeUpdate() != 1) throw new IllegalStateException("El insumo ya no existe");
                    }
                } else {
                    // El costo empieza en 0 (por defecto): se llena con la primera compra
                    String sql = "INSERT INTO insumos (nombre, unidad_base, stock_minimo, id_proveedor, activo) "
                            + "VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, nombre);
                        ps.setString(2, unidad);
                        ps.setInt(3, stockMinimo);
                        if (idProveedor > 0) ps.setInt(4, idProveedor); else ps.setNull(4, java.sql.Types.INTEGER);
                        ps.setInt(5, activo ? 1 : 0);
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
    public void registrarCompra(int idInsumo, int cantidadBase, int totalCent, boolean actualizarCosto, boolean pagadoDesdeCaja,
                                int idUsuario) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                int idEgreso = 0; // el egreso que crea esta compra (0 = no se pagó con la caja)
                if (pagadoDesdeCaja) {
                    String egreso = "INSERT INTO egresos (id_turno, categoria, descripcion, monto_cent, pagado_desde_caja, id_usuario) "
                            + "SELECT t.id_turno, 'Insumos', 'Compra de ' || i.nombre, ?, 1, ? FROM turnos_caja t, insumos i "
                            + "WHERE t.estado = 'Abierta' AND i.id_insumo = ? AND i.activo = 1";
                    try (PreparedStatement ps = c.prepareStatement(egreso)) {
                        ps.setInt(1, totalCent);
                        ps.setInt(2, idUsuario);
                        ps.setInt(3, idInsumo);
                        if (ps.executeUpdate() != 1)
                            throw new IllegalStateException("Para pagar con efectivo de la caja primero hay que abrir la caja");
                    }
                    idEgreso = ultimoId(c);
                }
                // costo_anterior_x1000: el costo que tenía el insumo antes de esta compra (solo si la compra lo actualiza),
                // para poder devolverlo si después se revierte la compra
                String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota, id_usuario, costo_anterior_x1000) "
                        + "SELECT i.id_insumo, 'Compra', ?, COALESCE(p.nombre, 'Compra'), ?, "
                        + "CASE WHEN ? = 1 THEN i.costo_cent_x1000 ELSE NULL END FROM insumos i "
                        + "LEFT JOIN proveedores p ON p.id_proveedor = i.id_proveedor WHERE i.id_insumo = ? AND i.activo = 1";
                try (PreparedStatement ps = c.prepareStatement(mov)) {
                    ps.setInt(1, cantidadBase);
                    ps.setInt(2, idUsuario);
                    ps.setInt(3, actualizarCosto ? 1 : 0);
                    ps.setInt(4, idInsumo);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("El insumo no existe o está desactivado");
                }
                int idMovimiento = ultimoId(c);
                if (idEgreso > 0) { // egreso y compra quedan ligados: así se puede revertir el inventario al anular el egreso
                    try (PreparedStatement ps = c.prepareStatement("UPDATE egresos SET id_movimiento_inventario = ? WHERE id_egreso = ?")) {
                        ps.setInt(1, idMovimiento);
                        ps.setInt(2, idEgreso);
                        ps.executeUpdate();
                    }
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

    private static int ultimoId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Revierte en el inventario la compra que originó un egreso de caja: resta lo comprado del stock y deja un
     * movimiento "Devolucion" en el historial del insumo. Se usa al anular el egreso, DENTRO de la transacción de
     * EgresoDAO.anular: si algo falla, el egreso tampoco queda anulado.
     * Si la compra había cambiado el costo y nadie lo ha vuelto a cambiar, el costo anterior se restaura.
     * Devuelve true si se restauró el costo.
     */
    public static boolean revertirCompra(Connection c, int idEgreso, String motivo, int idUsuario) throws SQLException {
        String leer = "SELECT m.id_insumo, m.cantidad, m.costo_anterior_x1000, e.monto_cent, i.stock, i.costo_cent_x1000, "
                + "i.unidad_base, i.nombre FROM egresos e "
                + "JOIN movimientos_inventario m ON m.id_movimiento = e.id_movimiento_inventario "
                + "JOIN insumos i ON i.id_insumo = m.id_insumo WHERE e.id_egreso = ?";
        int idInsumo, comprado, monto, stock, costoActual;
        Integer costoAnterior;
        String unidad, nombre;
        try (PreparedStatement ps = c.prepareStatement(leer)) {
            ps.setInt(1, idEgreso);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Este egreso no viene de una compra de inventario");
                idInsumo = rs.getInt(1);
                comprado = rs.getInt(2);
                int anterior = rs.getInt(3);
                costoAnterior = rs.wasNull() ? null : anterior;
                monto = rs.getInt(4);
                stock = rs.getInt(5);
                costoActual = rs.getInt(6);
                unidad = rs.getString(7);
                nombre = rs.getString(8);
            }
        }
        if (stock - comprado < 0) {
            throw new IllegalStateException("No se puede revertir en el inventario: de " + nombre + " hay " + legible(stock, unidad)
                    + " y la compra fue de " + legible(comprado, unidad) + ". Desmarca la casilla o ajusta el stock primero");
        }
        String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota, id_usuario) VALUES (?, 'Devolucion', ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(mov)) {
            ps.setInt(1, idInsumo);
            ps.setInt(2, -comprado);
            ps.setString(3, "Compra anulada: " + motivo);
            ps.setInt(4, idUsuario);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE insumos SET stock = stock - ? WHERE id_insumo = ?")) {
            ps.setInt(1, comprado);
            ps.setInt(2, idInsumo);
            ps.executeUpdate();
        }
        // El costo solo se devuelve si sigue siendo el que puso ESTA compra (si hubo otra compra después, no se toca)
        int costoDeEstaCompra = (int) Math.round(monto * 1000.0 / comprado);
        if (costoAnterior != null && costoActual == costoDeEstaCompra) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE insumos SET costo_cent_x1000 = ? WHERE id_insumo = ?")) {
                ps.setInt(1, costoAnterior);
                ps.setInt(2, idInsumo);
                ps.executeUpdate();
            }
            return true;
        }
        return false;
    }

    /** Cantidad en unidad base para mostrarla en un mensaje: 18000 ml -> "18 L", 50 ud -> "50 ud". */
    private static String legible(int cantidad, String unidad) {
        if ("ud".equals(unidad)) return cantidad + " ud";
        return BigDecimal.valueOf(cantidad, 3).stripTrailingZeros().toPlainString() + ("g".equals(unidad) ? " kg" : " L");
    }

    /**
     * Ajuste manual con motivo. modo "conteo": cantidadBase es el stock real contado (el sistema calcula la diferencia).
     * modo "merma": cantidadBase es lo perdido (se resta, sin dejar el stock en negativo).
     */
    public void ajustar(int idInsumo, String modo, int cantidadBase, String motivo, int idUsuario) {
        boolean conteo = "conteo".equals(modo);
        if (!conteo && !"merma".equals(modo)) throw new IllegalArgumentException("Tipo de ajuste inválido");
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (conteo) {
                    String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota, id_usuario) "
                            + "SELECT id_insumo, 'Ajuste', ? - stock, ?, ? FROM insumos WHERE id_insumo = ? AND stock <> ?";
                    try (PreparedStatement ps = c.prepareStatement(mov)) {
                        ps.setInt(1, cantidadBase);
                        ps.setString(2, "Conteo: " + motivo);
                        ps.setInt(3, idUsuario);
                        ps.setInt(4, idInsumo);
                        ps.setInt(5, cantidadBase);
                        if (ps.executeUpdate() != 1) throw new IllegalArgumentException("El conteo es igual al stock actual: no hay nada que ajustar");
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE insumos SET stock = ? WHERE id_insumo = ?")) {
                        ps.setInt(1, cantidadBase);
                        ps.setInt(2, idInsumo);
                        ps.executeUpdate();
                    }
                } else {
                    String mov = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, nota, id_usuario) "
                            + "SELECT id_insumo, 'Ajuste', -?, ?, ? FROM insumos WHERE id_insumo = ? AND stock - ? >= 0";
                    try (PreparedStatement ps = c.prepareStatement(mov)) {
                        ps.setInt(1, cantidadBase);
                        ps.setString(2, "Merma: " + motivo);
                        ps.setInt(3, idUsuario);
                        ps.setInt(4, idInsumo);
                        ps.setInt(5, cantidadBase);
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
        // El usuario sale del movimiento; en movimientos de venta viejos (sin usuario) se toma el de la venta
        String sql = "SELECT m.creado_en, m.tipo, m.cantidad, "
                + "CASE WHEN m.id_venta IS NOT NULL THEN 'Venta #' || m.id_venta ELSE COALESCE(m.nota, '') END, "
                + "COALESCE(u.nombre || ' (' || u.usuario || ')', '') "
                + "FROM movimientos_inventario m "
                + "LEFT JOIN ventas v ON v.id_venta = m.id_venta "
                + "LEFT JOIN usuarios u ON u.id_usuario = COALESCE(m.id_usuario, v.id_usuario) "
                + "WHERE m.id_insumo = ? ORDER BY m.id_movimiento DESC LIMIT ?";
        List<MovimientoInsumo> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idInsumo);
            ps.setInt(2, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(new MovimientoInsumo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el historial", e);
        }
    }
}
