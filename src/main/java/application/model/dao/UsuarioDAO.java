package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Usuario;
import application.util.Clave;
import application.util.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class UsuarioDAO {

    /** Intentos seguidos que se permiten antes de bloquear la cuenta. */
    public static final int MAX_INTENTOS = 5;
    /** Minutos que dura el bloqueo. */
    public static final int MINUTOS_BLOQUEO = 5;

    /** Resultado de intentar entrar. Si usuario es null: bloqueadoMin > 0 = cuenta bloqueada; si no, datos incorrectos. */
    public record Intento(Usuario usuario, boolean debeCambiarClave, int bloqueadoMin, boolean seBloqueoAhora) {
    }

    /** Una fila de la pantalla de usuarios. */
    public record Fila(int id, String nombre, String usuario, String rol, boolean activo, String ultimoAcceso,
                       boolean bloqueado, boolean debeCambiarClave) {
    }

    /** Resultado de recuperar la cuenta con el código. usuario null = no se pudo. */
    public record Recuperacion(Usuario usuario, String codigoNuevo, int bloqueadoMin) {
    }

    // Para que "usuario inexistente" tarde lo mismo que "clave incorrecta" (no delatar qué usuarios existen)
    private static volatile String hashFalso;

    public int contar() {
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM usuarios")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la tabla de usuarios", e);
        }
    }

    /** Crea el usuario y devuelve su id. Si ya existe ese nombre de usuario, lanza un mensaje para mostrar tal cual. */
    public int crear(String nombre, String usuario, String clave, String rol, boolean debeCambiarClave) {
        String sql = "INSERT INTO usuarios (nombre, usuario, password_hash, rol, debe_cambiar_clave) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nombre);
            ps.setString(2, usuario);
            ps.setString(3, PasswordUtil.hash(clave));   // se guarda cifrada, nunca en texto plano
            ps.setString(4, rol);
            ps.setInt(5, debeCambiarClave ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE"))
                throw new IllegalStateException("Ya existe un usuario con ese nombre");
            throw new IllegalStateException("No se pudo crear el usuario", e);
        }
    }

    /** Valida usuario y contraseña, cuenta los fallos y bloquea temporalmente al pasarse de intentos. */
    public Intento intentarEntrar(String usuario, String clave) {
        String sql = "SELECT id_usuario, nombre, usuario, rol, password_hash, activo, debe_cambiar_clave, intentos_fallidos, "
                + "CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta > datetime('now','localtime') "
                + "THEN CAST((julianday(bloqueado_hasta) - julianday('now','localtime')) * 1440 AS INTEGER) + 1 ELSE 0 END "
                + "FROM usuarios WHERE usuario = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    PasswordUtil.verificar(clave, hashFalso());
                    return new Intento(null, false, 0, false);
                }
                int id = rs.getInt(1);
                int bloqueo = rs.getInt(9);
                if (bloqueo > 0) return new Intento(null, false, bloqueo, false);
                boolean bien = PasswordUtil.verificar(clave, rs.getString(5));
                boolean activo = rs.getInt(6) == 1;
                if (bien && activo) {
                    marcarAcceso(c, id);
                    return new Intento(new Usuario(id, rs.getString(2), rs.getString(3), rs.getString(4)),
                            rs.getInt(7) == 1, 0, false);
                }
                if (!activo) return new Intento(null, false, 0, false); // cuenta desactivada: mismo mensaje que clave mala
                boolean bloquear = registrarFallo(c, id, rs.getInt(8));
                return new Intento(null, false, bloquear ? MINUTOS_BLOQUEO : 0, bloquear);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo validar el usuario", e);
        }
    }

    public List<Fila> listar() {
        String sql = "SELECT id_usuario, nombre, usuario, rol, activo, ultimo_acceso, "
                + "CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta > datetime('now','localtime') THEN 1 ELSE 0 END, "
                + "debe_cambiar_clave FROM usuarios ORDER BY activo DESC, rol, nombre";
        List<Fila> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Fila(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1,
                        rs.getString(6), rs.getInt(7) == 1, rs.getInt(8) == 1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la lista de usuarios", e);
        }
        return lista;
    }

    public Fila buscar(int id) {
        for (Fila f : listar()) if (f.id() == id) return f;
        return null;
    }

    /** ¿La contraseña actual es correcta? (para cambiarla, no cuenta intentos). */
    public boolean claveCorrecta(int id, String clave) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT password_hash FROM usuarios WHERE id_usuario = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && PasswordUtil.verificar(clave, rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo validar la contraseña", e);
        }
    }

    /** La propia persona cambia su contraseña. */
    public void cambiarClave(int id, String nueva) {
        asignarClave(id, nueva, false);
    }

    /** El administrador define una contraseña temporal: la persona debe cambiarla al entrar. */
    public void restablecerClave(int id, String temporal) {
        asignarClave(id, temporal, true);
    }

    public void desbloquear(int id) {
        ejecutar("UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id_usuario = ?", id);
    }

    /**
     * Cambia nombre, rol y estado. Nunca deja el sistema sin un administrador activo.
     * Los mensajes sin causa están pensados para mostrarse a la persona.
     */
    public void actualizar(int id, String nombre, String rol, boolean activo) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                String rolActual = null;
                boolean activoActual = false;
                try (PreparedStatement ps = c.prepareStatement("SELECT rol, activo FROM usuarios WHERE id_usuario = ?")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            rolActual = rs.getString(1);
                            activoActual = rs.getInt(2) == 1;
                        }
                    }
                }
                if (rolActual == null) throw new IllegalStateException("No existe ese usuario");
                boolean eraAdminActivo = "Administrador".equals(rolActual) && activoActual;
                boolean seguiraAdminActivo = "Administrador".equals(rol) && activo;
                if (eraAdminActivo && !seguiraAdminActivo) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT COUNT(*) FROM usuarios WHERE rol = 'Administrador' AND activo = 1 AND id_usuario <> ?")) {
                        ps.setInt(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getInt(1) == 0)
                                throw new IllegalStateException("Debe quedar al menos un administrador activo");
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE usuarios SET nombre = ?, rol = ?, activo = ? WHERE id_usuario = ?")) {
                    ps.setString(1, nombre);
                    ps.setString(2, rol);
                    ps.setInt(3, activo ? 1 : 0);
                    ps.setInt(4, id);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo actualizar el usuario", e);
        }
    }

    // ---------- Código de recuperación (solo administradores) ----------

    /** Genera un código nuevo para ese administrador y lo guarda cifrado. Devuelve el código para mostrarlo UNA vez. */
    public String nuevoCodigoRecuperacion(int id) {
        String codigo = Clave.codigoRecuperacion();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET recuperacion_hash = ? WHERE id_usuario = ?")) {
            ps.setString(1, PasswordUtil.hash(Clave.normalizarCodigo(codigo)));
            ps.setInt(2, id);
            ps.executeUpdate();
            return codigo;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar el código de recuperación", e);
        }
    }

    /** Si el código es correcto cambia la contraseña del administrador y entrega un código nuevo. */
    public Recuperacion recuperar(String usuario, String codigo, String nuevaClave) {
        String sql = "SELECT id_usuario, nombre, usuario, rol, recuperacion_hash, activo, intentos_fallidos, "
                + "CASE WHEN bloqueado_hasta IS NOT NULL AND bloqueado_hasta > datetime('now','localtime') "
                + "THEN CAST((julianday(bloqueado_hasta) - julianday('now','localtime')) * 1440 AS INTEGER) + 1 ELSE 0 END "
                + "FROM usuarios WHERE usuario = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    PasswordUtil.verificar(codigo, hashFalso());
                    return new Recuperacion(null, null, 0);
                }
                int id = rs.getInt(1);
                int bloqueo = rs.getInt(8);
                if (bloqueo > 0) return new Recuperacion(null, null, bloqueo);
                String guardado = rs.getString(5);
                boolean admin = "Administrador".equals(rs.getString(4)) && rs.getInt(6) == 1;
                boolean bien = guardado != null && PasswordUtil.verificar(Clave.normalizarCodigo(codigo), guardado);
                if (bien && admin) {
                    asignarClave(id, nuevaClave, false);
                    desbloquear(id);
                    String nuevo = nuevoCodigoRecuperacion(id);
                    return new Recuperacion(new Usuario(id, rs.getString(2), rs.getString(3), rs.getString(4)), nuevo, 0);
                }
                boolean bloquear = registrarFallo(c, id, rs.getInt(7));
                return new Recuperacion(null, null, bloquear ? MINUTOS_BLOQUEO : 0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo recuperar la cuenta", e);
        }
    }

    // ---------- Internos ----------

    private void asignarClave(int id, String clave, boolean debeCambiar) {
        String sql = "UPDATE usuarios SET password_hash = ?, debe_cambiar_clave = ?, intentos_fallidos = 0, bloqueado_hasta = NULL "
                + "WHERE id_usuario = ?";
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, PasswordUtil.hash(clave));
            ps.setInt(2, debeCambiar ? 1 : 0);
            ps.setInt(3, id);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("No existe ese usuario");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cambiar la contraseña", e);
        }
    }

    private void ejecutar(String sql, int id) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo actualizar el usuario", e);
        }
    }

    private static void marcarAcceso(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL, "
                + "ultimo_acceso = datetime('now','localtime') WHERE id_usuario = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /** Suma un fallo; al llegar al máximo bloquea la cuenta unos minutos. Devuelve true si se bloqueó. */
    private static boolean registrarFallo(Connection c, int id, int intentosAntes) throws SQLException {
        int intentos = intentosAntes + 1;
        if (intentos >= MAX_INTENTOS) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET intentos_fallidos = 0, "
                    + "bloqueado_hasta = datetime('now','localtime','+" + MINUTOS_BLOQUEO + " minutes') WHERE id_usuario = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
            return true;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET intentos_fallidos = ? WHERE id_usuario = ?")) {
            ps.setInt(1, intentos);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
        return false;
    }

    private static String hashFalso() {
        String h = hashFalso;
        if (h == null) {
            h = PasswordUtil.hash("clave-falsa-para-igualar-tiempos");
            hashFalso = h;
        }
        return h;
    }
}
