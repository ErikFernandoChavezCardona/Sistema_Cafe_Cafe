package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.LineaReceta;
import application.model.entity.Producto;
import application.model.entity.ProductoAdmin;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

    // ---------- Pantalla Productos (administración) ----------

    /**
     * Cuenta las líneas de la receta, cuántas tienen un insumo sin costo y suma el costo.
     * El costo de cada línea se redondea por separado (igual que LineaReceta.costoCent) para que cuadre.
     */
    private static final String SQL_ADMIN =
            "SELECT p.id_producto, p.id_categoria, c.nombre, p.nombre, p.precio_cent, p.activo, "
                    + "(SELECT COUNT(*) FROM recetas r WHERE r.id_producto = p.id_producto), "
                    + "(SELECT COUNT(*) FROM recetas r JOIN insumos i ON i.id_insumo = r.id_insumo "
                    + "  WHERE r.id_producto = p.id_producto AND (i.costo_cent_x1000 = 0 OR i.activo = 0)), "
                    + "(SELECT CAST(COALESCE(SUM(ROUND(r.cantidad * i.costo_cent_x1000 / 1000.0)), 0) AS INTEGER) "
                    + "  FROM recetas r JOIN insumos i ON i.id_insumo = r.id_insumo WHERE r.id_producto = p.id_producto AND i.activo = 1) "
                    + "FROM productos p JOIN categorias c ON c.id_categoria = p.id_categoria ";

    private static ProductoAdmin leer(ResultSet rs) throws SQLException {
        return new ProductoAdmin(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getInt(6) == 1, rs.getInt(7), rs.getInt(8), rs.getInt(9));
    }

    /** Todos los productos, también los inactivos, en el orden del menú. */
    public List<ProductoAdmin> todos() {
        List<ProductoAdmin> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(SQL_ADMIN + "ORDER BY c.orden, p.id_producto")) {
            while (rs.next()) lista.add(leer(rs));
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los productos", e);
        }
    }

    /** La receta de un producto, con el costo de cada insumo. */
    public List<LineaReceta> receta(int idProducto) {
        String sql = "SELECT i.id_insumo, i.nombre, i.unidad_base, r.cantidad, i.costo_cent_x1000 "
                + "FROM recetas r JOIN insumos i ON i.id_insumo = r.id_insumo WHERE r.id_producto = ? ORDER BY i.nombre";
        List<LineaReceta> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idProducto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new LineaReceta(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
                }
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la receta", e);
        }
    }

    /**
     * Crea (id == null) o actualiza un producto y reemplaza su receta, todo en UNA transacción:
     * si algo falla no queda un producto a medias. receta: cada elemento es {idInsumo, cantidad}.
     * Un cambio de precio solo afecta ventas nuevas: detalle_ventas ya guardó el precio de cada venta.
     */
    public int guardar(Integer id, int idCategoria, String nombre, int precioCent, boolean activo, List<int[]> receta) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                int idProducto;
                if (id == null) {
                    String insertar = "INSERT INTO productos (id_categoria, nombre, precio_cent, activo) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement ps = c.prepareStatement(insertar)) {
                        ps.setInt(1, idCategoria);
                        ps.setString(2, nombre);
                        ps.setInt(3, precioCent);
                        ps.setInt(4, activo ? 1 : 0);
                        ps.executeUpdate();
                    }
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                        rs.next();
                        idProducto = rs.getInt(1);
                    }
                } else {
                    String actualizar = "UPDATE productos SET id_categoria = ?, nombre = ?, precio_cent = ?, activo = ? WHERE id_producto = ?";
                    try (PreparedStatement ps = c.prepareStatement(actualizar)) {
                        ps.setInt(1, idCategoria);
                        ps.setString(2, nombre);
                        ps.setInt(3, precioCent);
                        ps.setInt(4, activo ? 1 : 0);
                        ps.setInt(5, id);
                        if (ps.executeUpdate() != 1) throw new IllegalStateException("El producto ya no existe");
                    }
                    idProducto = id;
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM recetas WHERE id_producto = ?")) {
                    ps.setInt(1, idProducto);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO recetas (id_producto, id_insumo, cantidad) VALUES (?, ?, ?)")) {
                    for (int[] linea : receta) {
                        ps.setInt(1, idProducto);
                        ps.setInt(2, linea[0]);
                        ps.setInt(3, linea[1]);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return idProducto;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            // Sin "cause": es un mensaje pensado para mostrarle al administrador
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalStateException("Ya existe un producto con ese nombre en esa categoría");
            }
            throw new IllegalStateException("No se pudo guardar el producto", e);
        }
    }

    /** Muestra u oculta un producto en Venta. Nunca se borra: las ventas viejas lo siguen usando. */
    public void cambiarActivo(int idProducto, boolean activo) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE productos SET activo = ? WHERE id_producto = ?")) {
            ps.setInt(1, activo ? 1 : 0);
            ps.setInt(2, idProducto);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cambiar el estado del producto", e);
        }
    }
}
