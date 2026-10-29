package application.controller;

import application.model.dao.AuditoriaDAO;
import application.model.dao.CategoriaDAO;
import application.model.dao.ProductoDAO;
import application.model.dao.TurnoDAO;
import application.model.dao.UsuarioDAO;
import application.model.entity.Categoria;
import application.model.entity.Producto;
import application.model.entity.Usuario;
import application.util.Clave;
import application.util.Json;
import application.util.Permisos;
import application.util.Registro;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

import java.util.Objects;

/**
 * Puente entre el HTML y Java. Los métodos PÚBLICOS se llaman desde JavaScript como javaApp.metodo(...).
 * Aquí viven la sesión (quién entró), el login con bloqueo por intentos y la navegación protegida por rol.
 */
public class WebBridge {

    private static final String FALLO = "{\"ok\":false}";

    private final WebEngine engine;
    private final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private final CategoriaDAO categoriaDAO = new CategoriaDAO();
    private final ProductoDAO productoDAO = new ProductoDAO();
    private final TurnoDAO turnoDAO = new TurnoDAO();
    private Usuario usuarioActual;
    /** Un administrador le puso una contraseña temporal: hasta que la cambie no puede usar nada más. */
    private boolean debeCambiarClave;

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
            Registro.error(e);
            return true;
        }
    }

    /**
     * Solo funciona en el primer arranque, cuando todavía no existe ningún usuario.
     * Devuelve {"ok":true,"codigo":"XXXX-XXXX-XXXX-XXXX"}: el código de recuperación se muestra UNA vez.
     * Después la página llama a continuar().
     */
    public String crearAdmin(String usuario, String clave) {
        try {
            if (hayUsuarios()) return FALLO;
            String u = usuario == null ? "" : usuario.trim();
            String motivo = Clave.validarUsuario(u);
            if (motivo == null) motivo = Clave.validar(clave, u);
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            int id = usuarioDAO.crear("Administrador", u, clave, "Administrador", false);
            String codigo = usuarioDAO.nuevoCodigoRecuperacion(id);
            iniciarSesion(new Usuario(id, "Administrador", u, "Administrador"), false);
            AuditoriaDAO.registrar(usuarioActual, "Administrador creado", "Primer arranque del sistema");
            return "{\"ok\":true,\"codigo\":" + Json.texto(codigo) + "}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo crear el usuario");
            return FALLO;
        }
    }

    public void login(String usuario, String clave) {
        try {
            String u = usuario == null ? "" : usuario.trim();
            if (u.isEmpty() || clave == null || clave.isEmpty()) {
                error("Escribe tu usuario y tu contraseña");
                return;
            }
            UsuarioDAO.Intento r = usuarioDAO.intentarEntrar(u, clave);
            if (r.usuario() == null) {
                AuditoriaDAO.registrar((Integer) null, recortar(u), r.seBloqueoAhora() ? "Cuenta bloqueada" : "Inicio de sesión fallido",
                        r.bloqueadoMin() > 0 ? "Bloqueo temporal por intentos fallidos" : "Usuario o contraseña incorrectos");
                error(r.bloqueadoMin() > 0 ? mensajeBloqueo(r.bloqueadoMin()) : "Usuario o contraseña incorrectos");
                return;
            }
            iniciarSesion(r.usuario(), r.debeCambiarClave());
            AuditoriaDAO.registrar(usuarioActual, "Inicio de sesión", "");
            irA(destinoInicial());
        } catch (Exception e) {
            Registro.error(e);
            error("Error del sistema, intenta de nuevo");
        }
    }

    /**
     * Para el administrador que olvidó su contraseña: usuario + código de recuperación + contraseña nueva.
     * Devuelve {"ok":true,"codigo":"..."} con el código NUEVO (el anterior queda inválido).
     */
    public String recuperar(String usuario, String codigo, String nuevaClave) {
        try {
            String u = usuario == null ? "" : usuario.trim();
            String motivo = Clave.validar(nuevaClave, u);
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            UsuarioDAO.Recuperacion r = usuarioDAO.recuperar(u, codigo == null ? "" : codigo, nuevaClave);
            if (r.usuario() == null) {
                AuditoriaDAO.registrar((Integer) null, recortar(u), "Recuperación fallida", "Código incorrecto o cuenta no válida");
                error(r.bloqueadoMin() > 0 ? mensajeBloqueo(r.bloqueadoMin()) : "Usuario o código de recuperación incorrectos");
                return FALLO;
            }
            iniciarSesion(r.usuario(), false);
            AuditoriaDAO.registrar(usuarioActual, "Contraseña recuperada", "Con el código de recuperación");
            return "{\"ok\":true,\"codigo\":" + Json.texto(r.codigoNuevo()) + "}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo recuperar la cuenta");
            return FALLO;
        }
    }

    /** Después de ver el código de recuperación (o de cambiar la contraseña obligatoria): entrar al sistema. */
    public void continuar() {
        if (usuarioActual == null) {
            irA("login.html");
            return;
        }
        irA(destinoInicial());
    }

    public void cerrarSesion() {
        try {
            if (usuarioActual != null) AuditoriaDAO.registrar(usuarioActual, "Cierre de sesión", "");
        } finally {
            usuarioActual = null;
            debeCambiarClave = false;
            irA("login.html");
        }
    }

    /** Quién está dentro, para el menú: {"id":1,"nombre":"..","usuario":"..","rol":"..","admin":true,"cambiar":false} o "null". */
    public String sesion() {
        if (usuarioActual == null) return "null";
        return "{\"id\":" + usuarioActual.id() + ",\"nombre\":" + Json.texto(usuarioActual.nombre())
                + ",\"usuario\":" + Json.texto(usuarioActual.usuario()) + ",\"rol\":" + Json.texto(usuarioActual.rol())
                + ",\"admin\":" + usuarioActual.esAdministrador() + ",\"cambiar\":" + debeCambiarClave + "}";
    }

    // ---------- Mi cuenta ----------

    /** La persona cambia su propia contraseña (también cuando es obligatorio). */
    public String cambiarMiClave(String actual, String nueva) {
        try {
            if (usuarioActual == null) {
                irA("login.html");
                return FALLO;
            }
            if (actual == null || !usuarioDAO.claveCorrecta(usuarioActual.id(), actual)) {
                error("La contraseña actual no es correcta");
                return FALLO;
            }
            String motivo = Clave.validar(nueva, usuarioActual.usuario());
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            if (nueva.equals(actual)) {
                error("La contraseña nueva debe ser diferente a la actual");
                return FALLO;
            }
            usuarioDAO.cambiarClave(usuarioActual.id(), nueva);
            debeCambiarClave = false;
            AuditoriaDAO.registrar(usuarioActual, "Contraseña cambiada", "La cambió la misma persona");
            return "{\"ok\":true}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo cambiar la contraseña");
            return FALLO;
        }
    }

    /** Solo administradores: genera un código de recuperación nuevo (pide la contraseña actual). */
    public String nuevoCodigoRecuperacion(String actual) {
        try {
            Usuario u = usuarioActual;
            if (u == null || !u.esAdministrador()) {
                error("Solo el administrador puede hacer esto");
                return FALLO;
            }
            if (actual == null || !usuarioDAO.claveCorrecta(u.id(), actual)) {
                error("La contraseña actual no es correcta");
                return FALLO;
            }
            String codigo = usuarioDAO.nuevoCodigoRecuperacion(u.id());
            AuditoriaDAO.registrar(u, "Código de recuperación nuevo", "");
            return "{\"ok\":true,\"codigo\":" + Json.texto(codigo) + "}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo generar el código");
            return FALLO;
        }
    }

    // ---------- Caja (turno) ----------

    public boolean hayTurnoAbierto() {
        try {
            return turnoDAO.abierto() != null;
        } catch (Exception e) {
            Registro.error(e);
            return false;
        }
    }

    /** El monto llega en centavos, como texto (L 500.00 llega como "50000"). */
    public void abrirTurno(String centavos) {
        try {
            Usuario u = usuarioActual;
            if (u == null) {
                irA("login.html");
                return;
            }
            if (!Permisos.puede(u, Permisos.Accion.ABRIR_CERRAR_CAJA)) {
                error("No tienes permiso para abrir la caja");
                return;
            }
            int monto = Integer.parseInt(centavos.trim());
            if (monto < 0) {
                error("El monto no puede ser negativo");
                return;
            }
            turnoDAO.abrir(u.id(), monto);
            AuditoriaDAO.registrar(u, "Caja abierta", "Fondo inicial: L " + String.format("%.2f", monto / 100.0));
            irA("pos.html");
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo abrir la caja (puede que ya haya una abierta)");
        }
    }

    // ---------- Punto de venta ----------

    /** Categorías y productos activos, en JSON. Los precios van en centavos. */
    public String catalogo() {
        try {
            if (usuarioActual == null) return "{\"categorias\":[],\"productos\":[]}";
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
            Registro.error(e);
            return "{\"categorias\":[],\"productos\":[]}";
        }
    }

    /**
     * Para los otros puentes (mismo paquete): quién inició sesión. Sin "public" para que el HTML no pueda llamarlo.
     * Mientras la persona deba cambiar su contraseña temporal devuelve null: ningún puente le responde.
     */
    Usuario usuarioActual() {
        return debeCambiarClave ? null : usuarioActual;
    }

    // ---------- Navegación y errores ----------

    public void irA(String pagina) {
        String destino = pagina == null ? "" : pagina.trim();
        if (!destino.matches("[a-z_]+\\.html")) {
            Registro.info("Página no válida: " + destino);
            return;
        }
        if (debeCambiarClave && !"login.html".equals(destino)) destino = "cuenta.html";
        if (!Permisos.puedeAbrir(usuarioActual, destino)) {
            if (usuarioActual == null) {
                destino = "login.html";
            } else {
                AuditoriaDAO.registrar(usuarioActual, "Acceso denegado", "Intentó abrir " + destino);
                error("No tienes permiso para entrar ahí");
                return;
            }
        }
        final String url = destino;
        Platform.runLater(() -> engine.load(pagina(url)));
    }

    private void iniciarSesion(Usuario u, boolean cambiarClave) {
        usuarioActual = u;
        debeCambiarClave = cambiarClave;
    }

    /** Sin caja abierta no se puede vender: primero se abre el turno. */
    private String destinoInicial() {
        if (debeCambiarClave) return "cuenta.html";
        return hayTurnoAbierto() ? "pos.html" : "caja.html";
    }

    private static String mensajeBloqueo(int minutos) {
        return "Demasiados intentos. Espera " + minutos + (minutos == 1 ? " minuto" : " minutos") + " e intenta de nuevo";
    }

    private static String recortar(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
