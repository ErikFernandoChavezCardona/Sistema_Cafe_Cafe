package application.controller;

import application.model.dao.UsuarioDAO;
import application.model.entity.Usuario;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

import java.util.Objects;

/**
 * Puente entre el HTML y Java. Los métodos PÚBLICOS se llaman desde JavaScript como javaApp.metodo(...).
 * Cada pantalla nueva agrega aquí sus métodos (o tendrá su propia clase en este paquete).
 */
public class WebBridge {

    private final WebEngine engine;
    private final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private Usuario usuarioActual;

    public WebBridge(WebEngine engine) {
        this.engine = engine;
    }

    /** Dirección de una página dentro de resources/application/web. */
    public static String pagina(String nombre) {
        return Objects.requireNonNull(
                WebBridge.class.getResource("/application/web/" + nombre),
                "No existe la página: " + nombre).toExternalForm();
    }

    // ---------- Login ----------

    public boolean hayUsuarios() {
        try {
            return usuarioDAO.contar() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    /** Solo funciona en el primer arranque, cuando todavía no existe ningún usuario. */
    public void crearAdmin(String usuario, String clave) {
        try {
            if (hayUsuarios()) return;
            if (usuario == null || usuario.isBlank() || clave == null || clave.length() < 6) {
                error("Usa un usuario y una contraseña de al menos 6 caracteres");
                return;
            }
            usuarioDAO.crear("Administrador", usuario.trim(), clave, "Administrador");
            login(usuario.trim(), clave);
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo crear el usuario");
        }
    }

    public void login(String usuario, String clave) {
        try {
            Usuario u = usuarioDAO.autenticar(usuario == null ? "" : usuario.trim(), clave == null ? "" : clave);
            if (u == null) {
                error("Usuario o contraseña incorrectos");
                return;
            }
            usuarioActual = u;
            irA("pos.html");
        } catch (Exception e) {
            e.printStackTrace();
            error("Error del sistema, intenta de nuevo");
        }
    }

    public void irA(String pagina) {
        Platform.runLater(() -> engine.load(pagina(pagina)));
    }

    private void error(String mensaje) {
        engine.executeScript("window.loginError && window.loginError(\"" + mensaje + "\")");
    }
}
