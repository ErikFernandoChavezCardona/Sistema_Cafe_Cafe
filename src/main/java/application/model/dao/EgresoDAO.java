package application.model.dao;

import application.model.connection.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class EgresoDAO {

    /**
     * Registra un gasto pagado con el efectivo de la caja (por eso pagado_desde_caja = 1:
     * es lo que hace que se descuente del efectivo esperado).
     * La lista de egresos sale junta con las ventas e ingresos en TurnoDAO.movimientos().
     */
    public void registrar(int idTurno, String categoria, String descripcion, int montoCent) {
        String sql = "INSERT INTO egresos (id_turno, categoria, descripcion, monto_cent, pagado_desde_caja) VALUES (?, ?, ?, ?, 1)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idTurno);
            ps.setString(2, categoria);
            ps.setString(3, descripcion);
            ps.setInt(4, montoCent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar el egreso", e);
        }
    }
}
