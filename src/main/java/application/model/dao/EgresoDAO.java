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

    /**
     * Anula un egreso (no lo borra). Solo se puede mientras el turno siga abierto: el filtro va dentro
     * del UPDATE para que no se pueda cambiar un turno ya cerrado ni anular dos veces lo mismo.
     */
    public void anular(int idEgreso, String motivo, int idUsuario) {
        String sql = "UPDATE egresos SET anulado = 1, motivo_anulacion = ?, id_usuario_anulo = ?, "
                + "anulado_en = datetime('now','localtime') WHERE id_egreso = ? AND anulado = 0 "
                + "AND id_turno IN (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta')";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, motivo);
            ps.setInt(2, idUsuario);
            ps.setInt(3, idEgreso);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("No se pudo anular: ya está anulado o la caja está cerrada");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo anular el egreso", e);
        }
    }
}
