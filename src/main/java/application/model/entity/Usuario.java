package application.model.entity;

/** Usuario del sistema (nunca guarda la contraseña). */
public record Usuario(int id, String nombre, String usuario, String rol) {
    public boolean esAdministrador() {
        return "Administrador".equals(rol);
    }
}
