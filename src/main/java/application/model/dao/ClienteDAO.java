package application.model.dao;

import application.model.connection.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Clientes frecuentes (RTN + nombre). Se llenan solos al emitir una factura con RTN. */
public class ClienteDAO {

    public record Cliente(String rtn, String nombre, int veces, String ultimaVez, boolean activo) {}

    /** Guarda o actualiza al cliente dentro de la transacción de la factura. rtn ya viene limpio (14 dígitos). */
    public static void recordar(Connection c, String rtn, String nombre) throws SQLException {
        if (rtn == null || !rtn.matches("\\d{14}") || nombre == null || nombre.isBlank()) return;
        String sql = "INSERT INTO clientes (rtn, nombre, veces) VALUES (?, ?, 1) "
                + "ON CONFLICT(rtn) DO UPDATE SET nombre = excluded.nombre, veces = veces + 1, "
                + "ultima_vez = datetime('now', 'localtime'), activo = 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, rtn);
            ps.setString(2, nombre.trim());
            ps.executeUpdate();
        }
    }

    /** Sugerencias para la caja: por nombre o por RTN, solo activos, los más usados primero. */
    public List<Cliente> buscar(String texto) {
        String t = texto == null ? "" : texto.trim();
        String digitos = t.replaceAll("\\D", "");
        List<Cliente> out = new ArrayList<>();
        if (t.length() < 2) return out;
        String sql = "SELECT rtn, nombre, veces, ultima_vez, activo FROM clientes WHERE activo = 1 AND (nombre LIKE ? "
                + (digitos.length() >= 3 ? "OR rtn LIKE ? " : "") + ") ORDER BY veces DESC, ultima_vez DESC LIMIT 6";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + t + "%");
            if (digitos.length() >= 3) ps.setString(2, digitos + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(leer(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron buscar los clientes", e);
        }
    }

    public List<Cliente> todos() {
        List<Cliente> out = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rtn, nombre, veces, ultima_vez, activo FROM clientes ORDER BY activo DESC, nombre");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(leer(rs));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los clientes", e);
        }
    }

    public void renombrar(String rtn, String nombre) {
        if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del cliente");
        ejecutar("UPDATE clientes SET nombre = ? WHERE rtn = ?", nombre.trim(), rtn);
    }

    public void activar(String rtn, boolean activo) {
        ejecutar("UPDATE clientes SET activo = ? WHERE rtn = ?", activo ? "1" : "0", rtn);
    }

    private void ejecutar(String sql, String a, String rtn) {
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, rtn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el cliente", e);
        }
    }

    private static Cliente leer(ResultSet rs) throws SQLException {
        return new Cliente(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getInt(5) == 1);
    }
}
