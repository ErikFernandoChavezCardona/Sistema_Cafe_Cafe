package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Comanda;
import application.model.entity.LineaVenta;
import application.model.entity.ResultadoCobro;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Ventas: guardar comandas pendientes, cobrar y cancelar.
 * Reglas: los precios SIEMPRE se leen de la base (nunca se confía en lo que manda la pantalla),
 * y cada operación que escribe en varias tablas va en UNA transacción.
 */
public class VentaDAO {

    private record Escrita(int id, int totalCent) {
    }

    /** Guarda la comanda como Pendiente. Si idVenta > 0 reemplaza esa pendiente. Devuelve el id de la venta. */
    public int guardarPendiente(int idVenta, int idUsuario, String nombre, List<LineaVenta> lineas) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                int id = escribir(c, idVenta, idUsuario, nombre, lineas).id();
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la comanda", e);
        }
    }

    /**
     * Cobra la venta: la guarda y la marca Completada en la misma transacción.
     * Efectivo: el recibido debe alcanzar y se calcula el cambio. Tarjeta o Transferencia: se cobra el total exacto.
     */
    public ResultadoCobro cobrar(int idVenta, int idUsuario, String nombre, List<LineaVenta> lineas,
                                 String metodo, int recibidoCent) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                Escrita e = escribir(c, idVenta, idUsuario, nombre, lineas);
                int recibido;
                int cambio;
                if ("Efectivo".equals(metodo)) {
                    if (recibidoCent < e.totalCent()) throw new IllegalArgumentException("El monto recibido no alcanza");
                    recibido = recibidoCent;
                    cambio = recibidoCent - e.totalCent();
                } else if ("Tarjeta".equals(metodo) || "Transferencia".equals(metodo)) {
                    recibido = e.totalCent();
                    cambio = 0;
                } else {
                    throw new IllegalArgumentException("Elige un método de pago");
                }
                String sql = "UPDATE ventas SET estado = 'Completada', metodo_pago = ?, recibido_cent = ?, cambio_cent = ?, "
                        + "cobrada_en = datetime('now','localtime') WHERE id_venta = ? AND estado = 'Pendiente'";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, metodo);
                    ps.setInt(2, recibido);
                    ps.setInt(3, cambio);
                    ps.setInt(4, e.id());
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("La comanda ya no está pendiente");
                }
                c.commit();
                return new ResultadoCobro(e.id(), e.totalCent(), recibido, cambio);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cobrar la venta", e);
        }
    }

    /** Las comandas pendientes, la más antigua primero (sin sus productos). */
    public List<Comanda> pendientes() {
        String sql = "SELECT id_venta, nombre_comanda, total_cent, creada_en FROM ventas "
                + "WHERE estado = 'Pendiente' ORDER BY creada_en, id_venta";
        List<Comanda> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Comanda(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), List.of()));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las comandas pendientes", e);
        }
    }

    /** Una comanda pendiente con sus productos, o null si ya no está pendiente. */
    public Comanda pendiente(int idVenta) {
        String cab = "SELECT nombre_comanda, total_cent, creada_en FROM ventas WHERE id_venta = ? AND estado = 'Pendiente'";
        String det = "SELECT id_producto, SUM(cantidad) FROM detalle_ventas WHERE id_venta = ? "
                + "GROUP BY id_producto ORDER BY MIN(id_detalle)";
        try (Connection c = DatabaseConnection.getConnection()) {
            String nombre;
            int total;
            String creada;
            try (PreparedStatement ps = c.prepareStatement(cab)) {
                ps.setInt(1, idVenta);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    nombre = rs.getString(1);
                    total = rs.getInt(2);
                    creada = rs.getString(3);
                }
            }
            List<LineaVenta> lineas = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(det)) {
                ps.setInt(1, idVenta);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) lineas.add(new LineaVenta(rs.getInt(1), rs.getInt(2)));
                }
            }
            return new Comanda(idVenta, nombre, total, creada, lineas);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la comanda", e);
        }
    }

    /** Cancela una comanda pendiente. No se borra: queda en el historial como Cancelada. */
    public void cancelarPendiente(int idVenta) {
        String sql = "UPDATE ventas SET estado = 'Cancelada' WHERE id_venta = ? AND estado = 'Pendiente'";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idVenta);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("La comanda ya no está pendiente");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cancelar la comanda", e);
        }
    }

    // ---------- interno ----------

    /**
     * Crea (o reemplaza) la venta Pendiente con sus líneas dentro de la transacción recibida.
     * La escritura va primero: toma el candado de la base y comprueba que haya caja abierta en el mismo paso.
     * La venta queda ligada al turno abierto AHORA (si venía de un turno anterior, el dinero entra en el de hoy).
     */
    private Escrita escribir(Connection c, int idVenta, int idUsuario, String nombre, List<LineaVenta> lineas)
            throws SQLException {
        if (lineas.isEmpty()) throw new IllegalArgumentException("La comanda está vacía");
        String nom = (nombre == null || nombre.isBlank()) ? "Sin nombre" : nombre.trim();
        int id = idVenta;

        if (id > 0) {
            String sql = "UPDATE ventas SET nombre_comanda = ?, "
                    + "id_turno = (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta') "
                    + "WHERE id_venta = ? AND estado = 'Pendiente' "
                    + "AND EXISTS (SELECT 1 FROM turnos_caja WHERE estado = 'Abierta')";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, nom);
                ps.setInt(2, id);
                if (ps.executeUpdate() != 1)
                    throw new IllegalStateException("La comanda ya no está pendiente o no hay una caja abierta");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM detalle_ventas WHERE id_venta = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        } else {
            String sql = "INSERT INTO ventas (id_turno, id_usuario, nombre_comanda) "
                    + "SELECT id_turno, ?, ? FROM turnos_caja WHERE estado = 'Abierta'";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, idUsuario);
                ps.setString(2, nom);
                if (ps.executeUpdate() != 1) throw new IllegalStateException("No hay una caja abierta");
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                id = rs.getInt(1);
            }
        }

        int total = 0;
        String ins = "INSERT INTO detalle_ventas (id_venta, id_producto, cantidad, precio_unit_cent, subtotal_cent) "
                + "VALUES (?, ?, ?, ?, ?)";
        for (LineaVenta l : lineas) {
            int precio = precioDe(c, l.idProducto());
            int subtotal = precio * l.cantidad();
            total += subtotal;
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                ps.setInt(1, id);
                ps.setInt(2, l.idProducto());
                ps.setInt(3, l.cantidad());
                ps.setInt(4, precio);
                ps.setInt(5, subtotal);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE ventas SET total_cent = ? WHERE id_venta = ?")) {
            ps.setInt(1, total);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
        return new Escrita(id, total);
    }

    private int precioDe(Connection c, int idProducto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT precio_cent FROM productos WHERE id_producto = ? AND activo = 1")) {
            ps.setInt(1, idProducto);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Un producto de la comanda ya no está disponible");
                return rs.getInt(1);
            }
        }
    }
}
