package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Turno;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class TurnoDAO {

    /** El turno abierto, o null si la caja está cerrada. */
    public Turno abierto() {
        String sql = "SELECT id_turno, monto_inicial_cent, apertura_en FROM turnos_caja WHERE estado = 'Abierta' LIMIT 1";
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? new Turno(rs.getInt(1), rs.getInt(2), rs.getString(3)) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el turno", e);
        }
    }

    /** Abre la caja. Si ya hay una abierta, la base lo rechaza (índice único). */
    public void abrir(int idUsuario, int montoInicialCent) {
        String sql = "INSERT INTO turnos_caja (id_usuario, monto_inicial_cent) VALUES (?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idUsuario);
            ps.setInt(2, montoInicialCent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo abrir la caja", e);
        }
    }
}
