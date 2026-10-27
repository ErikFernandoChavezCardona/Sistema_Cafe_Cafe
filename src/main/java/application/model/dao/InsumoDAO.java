package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Insumo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class InsumoDAO {

    /** Insumos activos para elegir en las recetas. Su costo se llena desde la pantalla Insumos. */
    public List<Insumo> activos() {
        String sql = "SELECT id_insumo, nombre, unidad_base, costo_cent_x1000 FROM insumos WHERE activo = 1 ORDER BY nombre";
        List<Insumo> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Insumo(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los insumos", e);
        }
    }

    /**
     * Descuenta del inventario lo que gastó una venta (según las recetas) y anota un movimiento "Venta" por insumo.
     * Recibe la conexión de VentaDAO.cobrar: corre DENTRO de su transacción, así que o se cobra y se descuenta
     * todo, o no pasa nada. El stock puede quedar negativo (se vendió sin tener): la pantalla Insumos lo marca Agotado.
     */
    public static void consumir(Connection c, int idVenta) throws SQLException {
        String movimientos = "INSERT INTO movimientos_inventario (id_insumo, tipo, cantidad, id_venta) "
                + "SELECT r.id_insumo, 'Venta', -SUM(r.cantidad * d.cantidad), d.id_venta "
                + "FROM detalle_ventas d JOIN recetas r ON r.id_producto = d.id_producto "
                + "WHERE d.id_venta = ? GROUP BY r.id_insumo, d.id_venta";
        String stock = "UPDATE insumos SET stock = stock + (SELECT SUM(m.cantidad) FROM movimientos_inventario m "
                + "WHERE m.id_venta = ? AND m.tipo = 'Venta' AND m.id_insumo = insumos.id_insumo) "
                + "WHERE id_insumo IN (SELECT id_insumo FROM movimientos_inventario WHERE id_venta = ? AND tipo = 'Venta')";
        try (PreparedStatement ps = c.prepareStatement(movimientos)) {
            ps.setInt(1, idVenta);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(stock)) {
            ps.setInt(1, idVenta);
            ps.setInt(2, idVenta);
            ps.executeUpdate();
        }
    }
}
