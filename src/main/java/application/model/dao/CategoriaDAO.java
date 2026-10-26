package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Categoria;
import application.model.entity.CategoriaAdmin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CategoriaDAO {

    public List<Categoria> activas() {
        String sql = "SELECT id_categoria, nombre, icono FROM categorias WHERE activo = 1 ORDER BY orden, id_categoria";
        List<Categoria> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Categoria(rs.getInt("id_categoria"), rs.getString("nombre"), rs.getString("icono")));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las categorías", e);
        }
    }

    // ---------- Pantalla Productos (administración) ----------

    /** Todas las categorías (también las ocultas) con su cantidad de productos, en el orden del menú. */
    public List<CategoriaAdmin> todas() {
        String sql = "SELECT c.id_categoria, c.nombre, c.icono, c.activo, "
                + "(SELECT COUNT(*) FROM productos p WHERE p.id_categoria = c.id_categoria) "
                + "FROM categorias c ORDER BY c.orden, c.id_categoria";
        List<CategoriaAdmin> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new CategoriaAdmin(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getInt(5)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las categorías", e);
        }
    }

    /** Crea una categoría al final del menú. El icono por defecto es el de la taza. */
    public void crear(String nombre) {
        String sql = "INSERT INTO categorias (nombre, orden) VALUES (?, (SELECT COALESCE(MAX(orden), 0) + 1 FROM categorias))";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nombre);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw nombreRepetido(e, "No se pudo crear la categoría");
        }
    }

    public void renombrar(int idCategoria, String nombre) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE categorias SET nombre = ? WHERE id_categoria = ?")) {
            ps.setString(1, nombre);
            ps.setInt(2, idCategoria);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw nombreRepetido(e, "No se pudo renombrar la categoría");
        }
    }

    /** Ocultar una categoría la quita de Venta junto con todos sus productos. */
    public void cambiarActivo(int idCategoria, boolean activa) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE categorias SET activo = ? WHERE id_categoria = ?")) {
            ps.setInt(1, activa ? 1 : 0);
            ps.setInt(2, idCategoria);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cambiar el estado de la categoría", e);
        }
    }

    /**
     * Sube (-1) o baja (+1) una categoría en el menú. Se reescribe el orden de todas (1, 2, 3...)
     * dentro de una transacción, así nunca quedan dos con el mismo número.
     */
    public void mover(int idCategoria, int direccion) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<Integer> ids = new ArrayList<>();
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT id_categoria FROM categorias ORDER BY orden, id_categoria")) {
                    while (rs.next()) ids.add(rs.getInt(1));
                }
                int pos = ids.indexOf(idCategoria);
                int destino = pos + direccion;
                if (pos < 0 || destino < 0 || destino >= ids.size()) {
                    c.rollback();
                    return; // ya está en el extremo: no hay nada que mover
                }
                ids.remove(pos);
                ids.add(destino, idCategoria);
                try (PreparedStatement ps = c.prepareStatement("UPDATE categorias SET orden = ? WHERE id_categoria = ?")) {
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setInt(1, i + 1);
                        ps.setInt(2, ids.get(i));
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo mover la categoría", e);
        }
    }

    private static IllegalStateException nombreRepetido(SQLException e, String generico) {
        if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
            return new IllegalStateException("Ya existe una categoría con ese nombre");
        }
        return new IllegalStateException(generico, e);
    }
}
