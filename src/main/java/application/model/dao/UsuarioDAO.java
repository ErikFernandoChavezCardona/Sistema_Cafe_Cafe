package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Usuario;
import application.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class UsuarioDAO {

    public int contar() {
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM usuarios")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la tabla de usuarios", e);
        }
    }

    public void crear(String nombre, String usuario, String clave, String rol) {
        String sql = "INSERT INTO usuarios (nombre, usuario, password_hash, rol) VALUES (?, ?, ?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nombre);
            ps.setString(2, usuario);
            ps.setString(3, PasswordUtil.hash(clave));   // se guarda cifrada, nunca en texto plano
            ps.setString(4, rol);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear el usuario", e);
        }
    }

    /** Devuelve el usuario si las credenciales son correctas, o null. */
    public Usuario autenticar(String usuario, String clave) {
        String sql = "SELECT id_usuario, nombre, usuario, rol, password_hash FROM usuarios WHERE usuario = ? AND activo = 1";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordUtil.verificar(clave, rs.getString("password_hash"))) {
                    return new Usuario(rs.getInt("id_usuario"), rs.getString("nombre"),
                            rs.getString("usuario"), rs.getString("rol"));
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo validar el usuario", e);
        }
    }
}
