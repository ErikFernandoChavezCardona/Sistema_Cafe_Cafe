package application.model.dao;

import application.model.connection.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class IngresoCajaDAO {

    /**
     * Efectivo que entra a la caja chica durante el turno (reposición de cambio, etc.).
     * Suma al efectivo esperado. La lista sale junta con las ventas y egresos en TurnoDAO.movimientos().
     */
    public void registrar(int idTurno, String concepto, int montoCent) {
        String sql = "INSERT INTO ingresos_caja (id_turno, concepto, monto_cent) VALUES (?, ?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idTurno);
            ps.setString(2, concepto);
            ps.setInt(3, montoCent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar el ingreso", e);
        }
    }
}
