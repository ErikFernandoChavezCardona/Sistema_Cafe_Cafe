package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Movimiento;
import application.model.entity.ResultadoCierre;
import application.model.entity.ResumenTurno;
import application.model.entity.Turno;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

    /** Cifras del turno (abierto o cerrado). */
    public ResumenTurno resumen(int idTurno) {
        try (Connection c = DatabaseConnection.getConnection()) {
            ResumenTurno r = resumen(c, idTurno);
            if (r == null) throw new IllegalStateException("No existe el turno " + idTurno);
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo calcular el resumen del turno", e);
        }
    }

    /** Ventas completadas, ingresos y egresos del turno, lo más reciente primero. */
    public List<Movimiento> movimientos(int idTurno) {
        // Las ventas Pendientes o Canceladas no son dinero real: solo cuentan las Completadas
        String sql = "SELECT 'Venta', nombre_comanda, metodo_pago, total_cent, COALESCE(cobrada_en, creada_en) AS h, "
                + "CASE WHEN metodo_pago = 'Efectivo' THEN 1 ELSE 0 END "
                + "FROM ventas WHERE id_turno = ? AND estado = 'Completada' "
                + "UNION ALL SELECT 'Ingreso', concepto, NULL, monto_cent, creado_en, 1 "
                + "FROM ingresos_caja WHERE id_turno = ? "
                + "UNION ALL SELECT 'Egreso', descripcion, categoria, monto_cent, creado_en, pagado_desde_caja "
                + "FROM egresos WHERE id_turno = ? "
                + "ORDER BY h DESC";
        List<Movimiento> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idTurno);
            ps.setInt(2, idTurno);
            ps.setInt(3, idTurno);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Movimiento(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getString(5), rs.getInt(6) == 1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los movimientos", e);
        }
        return lista;
    }

    /**
     * Cierra el turno en UNA transacción: o se guarda todo (hora, contado, esperado, diferencia) o nada.
     * La escritura va primero a propósito: toma el candado de la base antes de calcular, así nadie
     * puede registrar un egreso a mitad del cierre, y el "AND estado = 'Abierta'" impide cerrar dos veces.
     */
    public ResultadoCierre cerrar(int idTurno, int contadoCent, int idUsuarioCierre) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                String cerrar = "UPDATE turnos_caja SET estado = 'Cerrada', cierre_en = datetime('now','localtime'), "
                        + "monto_contado_cent = ?, id_usuario_cierre = ? WHERE id_turno = ? AND estado = 'Abierta'";
                try (PreparedStatement ps = c.prepareStatement(cerrar)) {
                    ps.setInt(1, contadoCent);
                    ps.setInt(2, idUsuarioCierre);
                    ps.setInt(3, idTurno);
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("El turno ya estaba cerrado");
                }

                ResumenTurno r = resumen(c, idTurno);
                int diferencia = contadoCent - r.esperadoCent();

                String guardar = "UPDATE turnos_caja SET monto_esperado_cent = ?, diferencia_cent = ? WHERE id_turno = ?";
                try (PreparedStatement ps = c.prepareStatement(guardar)) {
                    ps.setInt(1, r.esperadoCent());
                    ps.setInt(2, diferencia);
                    ps.setInt(3, idTurno);
                    ps.executeUpdate();
                }

                String cierreEn = null;
                String usuarioCerro = null;
                String leer = "SELECT t.cierre_en, u.nombre || ' (' || u.usuario || ')' FROM turnos_caja t "
                        + "JOIN usuarios u ON u.id_usuario = t.id_usuario_cierre WHERE t.id_turno = ?";
                try (PreparedStatement ps = c.prepareStatement(leer)) {
                    ps.setInt(1, idTurno);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            cierreEn = rs.getString(1);
                            usuarioCerro = rs.getString(2);
                        }
                    }
                }
                c.commit();
                return new ResultadoCierre(r, contadoCent, diferencia, cierreEn, usuarioCerro);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cerrar la caja", e);
        }
    }

    /** Un solo SELECT con subconsultas; recibe la conexión para poder usarse dentro del cierre. */
    private ResumenTurno resumen(Connection c, int idTurno) throws SQLException {
        String sql = "SELECT t.apertura_en, u.nombre || ' (' || u.usuario || ')', t.monto_inicial_cent, "
                + "(SELECT COALESCE(SUM(total_cent), 0) FROM ventas WHERE id_turno = t.id_turno AND estado = 'Completada' AND metodo_pago = 'Efectivo'), "
                + "(SELECT COALESCE(SUM(total_cent), 0) FROM ventas WHERE id_turno = t.id_turno AND estado = 'Completada' AND metodo_pago = 'Tarjeta'), "
                + "(SELECT COALESCE(SUM(total_cent), 0) FROM ventas WHERE id_turno = t.id_turno AND estado = 'Completada' AND metodo_pago = 'Transferencia'), "
                + "(SELECT COUNT(*) FROM ventas WHERE id_turno = t.id_turno AND estado = 'Completada'), "
                + "(SELECT COALESCE(SUM(monto_cent), 0) FROM ingresos_caja WHERE id_turno = t.id_turno), "
                + "(SELECT COALESCE(SUM(monto_cent), 0) FROM egresos WHERE id_turno = t.id_turno AND pagado_desde_caja = 1) "
                + "FROM turnos_caja t JOIN usuarios u ON u.id_usuario = t.id_usuario WHERE t.id_turno = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idTurno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ResumenTurno(idTurno, rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9));
            }
        }
    }
}
