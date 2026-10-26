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

    /** Anula un ingreso (no lo borra). Solo mientras el turno siga abierto; ver EgresoDAO.anular. */
    public void anular(int idIngreso, String motivo, int idUsuario) {
        String sql = "UPDATE ingresos_caja SET anulado = 1, motivo_anulacion = ?, id_usuario_anulo = ?, "
                + "anulado_en = datetime('now','localtime') WHERE id_ingreso = ? AND anulado = 0 "
                + "AND id_turno IN (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta')";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, motivo);
            ps.setInt(2, idUsuario);
            ps.setInt(3, idIngreso);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("No se pudo anular: ya está anulado o la caja está cerrada");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo anular el ingreso", e);
        }
    }
}
