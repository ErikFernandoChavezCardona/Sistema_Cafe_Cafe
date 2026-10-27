package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Proveedor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ProveedorDAO {

    /** Todos los proveedores (los desactivados al final). */
    public List<Proveedor> todos() {
        String sql = "SELECT p.id_proveedor, p.nombre, COALESCE(p.telefono, ''), COALESCE(p.rtn, ''), p.activo, "
                + "(SELECT COUNT(*) FROM insumos i WHERE i.id_proveedor = p.id_proveedor AND i.activo = 1) "
                + "FROM proveedores p ORDER BY p.activo DESC, p.nombre";
        List<Proveedor> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Proveedor(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5) == 1, rs.getInt(6)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los proveedores", e);
        }
    }

    /** Crea (id = 0) o edita un proveedor. Devuelve su id. Teléfono y RTN vacíos se guardan como "sin dato". */
    public int guardar(int id, String nombre, String telefono, String rtn) {
        try (Connection c = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM proveedores WHERE lower(nombre) = lower(?) AND id_proveedor <> ?")) {
                ps.setString(1, nombre);
                ps.setInt(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) throw new IllegalArgumentException("Ya existe un proveedor con ese nombre");
                }
            }
            if (id > 0) {
                String sql = "UPDATE proveedores SET nombre = ?, telefono = NULLIF(?, ''), rtn = NULLIF(?, '') WHERE id_proveedor = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, nombre);
                    ps.setString(2, telefono);
                    ps.setString(3, rtn);
                    ps.setInt(4, id);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("El proveedor ya no existe");
                }
                return id;
            }
            String sql = "INSERT INTO proveedores (nombre, telefono, rtn) VALUES (?, NULLIF(?, ''), NULLIF(?, ''))";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, nombre);
                ps.setString(2, telefono);
                ps.setString(3, rtn);
                ps.executeUpdate();
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el proveedor", e);
        }
    }

    /** Desactivar no borra: las compras anteriores lo siguen mencionando. */
    public void cambiarActivo(int id, boolean activo) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE proveedores SET activo = ? WHERE id_proveedor = ?")) {
            ps.setInt(1, activo ? 1 : 0);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("El proveedor ya no existe");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cambiar el proveedor", e);
        }
    }
}
