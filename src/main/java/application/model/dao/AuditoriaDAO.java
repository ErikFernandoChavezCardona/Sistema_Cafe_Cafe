package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.FilaAuditoria;
import application.model.entity.Usuario;
import application.util.Registro;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Bitácora de acciones sensibles. Registrar es "lo mejor posible": si falla se anota en el log y la operación sigue. */
public class AuditoriaDAO {

    public static void registrar(Usuario u, String accion, String detalle) {
        registrar(u == null ? null : u.id(), u == null ? "" : u.usuario(), accion, detalle);
    }

    public static void registrar(Integer idUsuario, String usuario, String accion, String detalle) {
        String sql = "INSERT INTO auditoria (id_usuario, usuario, accion, detalle) VALUES (?, ?, ?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (idUsuario == null) ps.setNull(1, java.sql.Types.INTEGER);
            else ps.setInt(1, idUsuario);
            ps.setString(2, usuario == null ? "" : usuario);
            ps.setString(3, accion);
            String d = detalle == null ? "" : detalle;
            ps.setString(4, d.length() > 500 ? d.substring(0, 500) : d);
            ps.executeUpdate();
        } catch (Exception e) {
            Registro.error("No se pudo escribir en la auditoría: " + accion, e);
        }
    }

    /** Las últimas entradas, filtrando por texto (usuario o detalle) y/o por acción. Máximo 'limite'. */
    public List<FilaAuditoria> recientes(String texto, String accion, int limite) {
        StringBuilder sql = new StringBuilder("SELECT fecha, usuario, accion, detalle FROM auditoria WHERE 1 = 1");
        List<String> args = new ArrayList<>();
        if (texto != null && !texto.isBlank()) {
            sql.append(" AND (usuario LIKE ? OR detalle LIKE ?)");
            String like = "%" + texto.trim().replace("%", "").replace("_", "") + "%";
            args.add(like);
            args.add(like);
        }
        if (accion != null && !accion.isBlank()) {
            sql.append(" AND accion = ?");
            args.add(accion);
        }
        sql.append(" ORDER BY id_auditoria DESC LIMIT ").append(Math.max(1, Math.min(limite, 1000)));
        List<FilaAuditoria> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new FilaAuditoria(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la auditoría", e);
        }
        return lista;
    }

    public List<String> acciones() {
        List<String> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DISTINCT accion FROM auditoria ORDER BY accion")) {
            while (rs.next()) lista.add(rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las acciones", e);
        }
        return lista;
    }
}
