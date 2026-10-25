package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Producto;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ProductoDAO {

    /** Productos activos (de categorías activas), en el mismo orden del menú. */
    public List<Producto> activos() {
        String sql = "SELECT p.id_producto, p.id_categoria, p.nombre, p.precio_cent, p.imagen "
                + "FROM productos p JOIN categorias c ON c.id_categoria = p.id_categoria "
                + "WHERE p.activo = 1 AND c.activo = 1 ORDER BY c.orden, p.id_producto";
        List<Producto> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Producto(rs.getInt("id_producto"), rs.getInt("id_categoria"),
                        rs.getString("nombre"), rs.getInt("precio_cent"), rs.getString("imagen")));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los productos", e);
        }
    }
}
