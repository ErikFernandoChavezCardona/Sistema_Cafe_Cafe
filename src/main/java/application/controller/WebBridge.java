package application.controller;

import application.model.dao.CategoriaDAO;
import application.model.dao.ProductoDAO;
import application.model.dao.TurnoDAO;
import application.model.dao.UsuarioDAO;
import application.model.entity.Categoria;
import application.model.entity.Producto;
import application.model.entity.Usuario;
import application.util.Json;
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
    private final CategoriaDAO categoriaDAO = new CategoriaDAO();
    private final ProductoDAO productoDAO = new ProductoDAO();
    private final TurnoDAO turnoDAO = new TurnoDAO();
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
            // Sin caja abierta no se puede vender: primero se abre el turno
            irA(hayTurnoAbierto() ? "pos.html" : "caja.html");
        } catch (Exception e) {
            e.printStackTrace();
            error("Error del sistema, intenta de nuevo");
        }
    }

    // ---------- Caja (turno) ----------

    public boolean hayTurnoAbierto() {
        try {
            return turnoDAO.abierto() != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** El monto llega en centavos, como texto (L 500.00 llega como "50000"). */
    public void abrirTurno(String centavos) {
        try {
            if (usuarioActual == null) {
                irA("login.html");
                return;
            }
            int monto = Integer.parseInt(centavos.trim());
            if (monto < 0) {
                error("El monto no puede ser negativo");
                return;
            }
            turnoDAO.abrir(usuarioActual.id(), monto);
            irA("pos.html");
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo abrir la caja (puede que ya haya una abierta)");
        }
    }

    // ---------- Punto de venta ----------

    /** Categorías y productos activos, en JSON. Los precios van en centavos. */
    public String catalogo() {
        try {
            StringBuilder b = new StringBuilder("{\"categorias\":[");
            boolean primero = true;
            for (Categoria c : categoriaDAO.activas()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(c.id()).append(",\"nombre\":").append(Json.texto(c.nombre()))
                        .append(",\"icono\":").append(Json.texto(c.icono())).append('}');
            }
            b.append("],\"productos\":[");
            primero = true;
            for (Producto p : productoDAO.activos()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(p.id()).append(",\"c\":").append(p.idCategoria())
                        .append(",\"n\":").append(Json.texto(p.nombre()))
                        .append(",\"p\":").append(p.precioCent()).append('}');
            }
            return b.append("]}").toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"categorias\":[],\"productos\":[]}";
        }
    }

    // ---------- Navegación y errores ----------

    public void irA(String pagina) {
        Platform.runLater(() -> engine.load(pagina(pagina)));
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
