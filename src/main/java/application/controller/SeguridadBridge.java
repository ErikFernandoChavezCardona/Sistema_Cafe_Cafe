package application.controller;

import application.model.connection.Respaldo;
import application.model.dao.AuditoriaDAO;
import application.model.dao.UsuarioDAO;
import application.model.entity.FilaAuditoria;
import application.model.entity.Usuario;
import application.util.Clave;
import application.util.Json;
import application.util.Registro;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Puente de Usuarios, Respaldos y Auditoría (usuarios.html, respaldos.html, auditoria.html). Se registra como
 * window.javaSeguridad. TODO es solo para administradores: cada método lo comprueba por su cuenta.
 */
public class SeguridadBridge {

    private static final String FALLO = "{\"ok\":false}";

    private final WebEngine engine;
    private final WebBridge web;
    private final UsuarioDAO usuarioDAO = new UsuarioDAO();
    private final AuditoriaDAO auditoriaDAO = new AuditoriaDAO();

    public SeguridadBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    // ---------- Usuarios ----------

    public String usuarios() {
        if (admin() == null) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (UsuarioDAO.Fila f : usuarioDAO.listar()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(f.id()).append(",\"nombre\":").append(Json.texto(f.nombre()))
                        .append(",\"usuario\":").append(Json.texto(f.usuario()))
                        .append(",\"rol\":").append(Json.texto(f.rol()))
                        .append(",\"activo\":").append(f.activo())
                        .append(",\"acceso\":").append(Json.texto(f.ultimoAcceso()))
                        .append(",\"bloqueado\":").append(f.bloqueado())
                        .append(",\"cambiar\":").append(f.debeCambiarClave()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo leer la lista de usuarios");
            return "[]";
        }
    }

    /** La contraseña que se escribe aquí es temporal: la persona deberá cambiarla al entrar por primera vez. */
    public String crearUsuario(String nombre, String usuario, String claveTemporal, String rol) {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            String n = nombre == null ? "" : nombre.trim();
            String u = usuario == null ? "" : usuario.trim();
            if (n.isEmpty() || n.length() > 60) {
                error("Escribe el nombre de la persona (máximo 60 letras)");
                return FALLO;
            }
            String motivo = Clave.validarUsuario(u);
            if (motivo == null) motivo = Clave.validar(claveTemporal, u);
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            String r = rolValido(rol);
            if (r == null) {
                error("Elige un rol");
                return FALLO;
            }
            usuarioDAO.crear(n, u, claveTemporal, r, true);
            AuditoriaDAO.registrar(yo, "Usuario creado", u + " (" + r + ")");
            return "{\"ok\":true}";
        } catch (IllegalStateException e) {
            return fallo(e, "No se pudo crear el usuario");
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo crear el usuario");
            return FALLO;
        }
    }

    /** activo: "1" o "0". No se puede quitar el rol ni desactivar a uno mismo. */
    public String actualizarUsuario(String id, String nombre, String rol, String activo) {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            int idu = Integer.parseInt(id.trim());
            String n = nombre == null ? "" : nombre.trim();
            String r = rolValido(rol);
            if (n.isEmpty() || n.length() > 60 || r == null) {
                error("Revisa el nombre y el rol");
                return FALLO;
            }
            boolean act = "1".equals(activo);
            if (idu == yo.id() && (!act || !r.equals(yo.rol()))) {
                error("No puedes desactivarte ni cambiarte el rol a ti mismo");
                return FALLO;
            }
            UsuarioDAO.Fila antes = usuarioDAO.buscar(idu);
            usuarioDAO.actualizar(idu, n, r, act);
            if (antes != null) {
                if (antes.activo() != act) AuditoriaDAO.registrar(yo, act ? "Usuario activado" : "Usuario desactivado", antes.usuario());
                if (!antes.rol().equals(r)) AuditoriaDAO.registrar(yo, "Rol cambiado", antes.usuario() + ": " + antes.rol() + " → " + r);
                if (!antes.nombre().equals(n)) AuditoriaDAO.registrar(yo, "Usuario editado", antes.usuario() + ": nombre " + antes.nombre() + " → " + n);
            }
            return "{\"ok\":true}";
        } catch (NumberFormatException e) {
            error("No se pudo identificar al usuario");
            return FALLO;
        } catch (IllegalStateException e) {
            return fallo(e, "No se pudo actualizar el usuario");
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo actualizar el usuario");
            return FALLO;
        }
    }

    /** Pone una contraseña temporal a otra persona (olvidó la suya). Deberá cambiarla al entrar. */
    public String restablecerClave(String id, String claveTemporal) {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            int idu = Integer.parseInt(id.trim());
            UsuarioDAO.Fila f = usuarioDAO.buscar(idu);
            if (f == null) {
                error("No existe ese usuario");
                return FALLO;
            }
            if (idu == yo.id()) {
                error("Tu propia contraseña se cambia desde \"Mi cuenta\"");
                return FALLO;
            }
            String motivo = Clave.validar(claveTemporal, f.usuario());
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            usuarioDAO.restablecerClave(idu, claveTemporal);
            AuditoriaDAO.registrar(yo, "Contraseña restablecida", f.usuario());
            return "{\"ok\":true}";
        } catch (NumberFormatException e) {
            error("No se pudo identificar al usuario");
            return FALLO;
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo restablecer la contraseña");
            return FALLO;
        }
    }

    public String desbloquear(String id) {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            int idu = Integer.parseInt(id.trim());
            UsuarioDAO.Fila f = usuarioDAO.buscar(idu);
            if (f == null) return FALLO;
            usuarioDAO.desbloquear(idu);
            AuditoriaDAO.registrar(yo, "Usuario desbloqueado", f.usuario());
            return "{\"ok\":true}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo desbloquear");
            return FALLO;
        }
    }

    // ---------- Respaldos ----------

    public String respaldos() {
        if (admin() == null) return "{}";
        try {
            StringBuilder b = new StringBuilder("{\"externa\":").append(Json.texto(Respaldo.carpetaExterna()))
                    .append(",\"ultimaLocal\":").append(Json.texto(Respaldo.ultimaLocal()))
                    .append(",\"ultimaExterna\":").append(Json.texto(Respaldo.ultimaExterna()))
                    .append(",\"copias\":[");
            boolean primero = true;
            for (Respaldo.Copia c : Respaldo.locales()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"n\":").append(Json.texto(c.nombre())).append(",\"b\":").append(c.bytes())
                        .append(",\"f\":").append(Json.texto(c.fecha())).append('}');
            }
            return b.append("]}").toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo leer la lista de respaldos");
            return "{}";
        }
    }

    public String respaldarAhora() {
        Usuario yo = admin();
        if (yo == null) return denegado();
        Respaldo.Resultado r = Respaldo.hacerCopia("Manual", yo);
        if (!r.ok()) error(r.mensaje());
        return "{\"ok\":" + r.ok() + ",\"mensaje\":" + Json.texto(r.mensaje()) + ",\"externoConfigurado\":" + r.externoConfigurado()
                + ",\"externoOk\":" + r.externoOk() + "}";
    }

    /** Abre el selector de carpetas de Windows para elegir dónde guardar la copia externa (USB, otro disco). */
    public String elegirCarpetaExterna() {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Carpeta para la copia de seguridad externa");
            File elegida = dc.showDialog(ventana());
            if (elegida == null) return "{\"ok\":false,\"cancelado\":true}";
            String motivo = Respaldo.fijarCarpetaExterna(elegida.getAbsolutePath());
            if (motivo != null) {
                error(motivo);
                return FALLO;
            }
            AuditoriaDAO.registrar(yo, "Carpeta de respaldo externo", elegida.getAbsolutePath());
            return "{\"ok\":true}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo elegir la carpeta");
            return FALLO;
        }
    }

    public String quitarCarpetaExterna() {
        Usuario yo = admin();
        if (yo == null) return denegado();
        Respaldo.fijarCarpetaExterna("");
        AuditoriaDAO.registrar(yo, "Carpeta de respaldo externo", "Desactivada");
        return "{\"ok\":true}";
    }

    /** Prepara la restauración de una copia de la lista. Se aplica al volver a abrir el programa. */
    public String restaurarLocal(String nombre) {
        Usuario yo = admin();
        if (yo == null) return denegado();
        Path p = Respaldo.rutaLocal(nombre);
        if (p == null) {
            error("No se encontró esa copia");
            return FALLO;
        }
        return preparar(yo, p, nombre);
    }

    /** Elegir un archivo .db de una USB u otra carpeta. */
    public String restaurarDesdeArchivo() {
        Usuario yo = admin();
        if (yo == null) return denegado();
        try {
            FileChooser fc = new FileChooser();
            fc.setTitle("Elige la copia de seguridad (.db)");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Copias de Café Café (*.db)", "*.db"));
            File f = fc.showOpenDialog(ventana());
            if (f == null) return "{\"ok\":false,\"cancelado\":true}";
            return preparar(yo, f.toPath(), f.getName());
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo abrir el archivo");
            return FALLO;
        }
    }

    /** Cierra el programa (después de pedir una restauración, para que se aplique al abrirlo de nuevo). */
    public void salir() {
        Usuario yo = admin();
        if (yo == null) return;
        AuditoriaDAO.registrar(yo, "Programa cerrado", "Desde la pantalla de respaldos");
        Platform.exit();
    }

    // ---------- Auditoría ----------

    public String auditoria(String texto, String accion) {
        if (admin() == null) return "{\"filas\":[],\"acciones\":[]}";
        try {
            List<FilaAuditoria> filas = auditoriaDAO.recientes(texto, accion, 300);
            StringBuilder b = new StringBuilder("{\"filas\":[");
            boolean primero = true;
            for (FilaAuditoria f : filas) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"f\":").append(Json.texto(f.fecha())).append(",\"u\":").append(Json.texto(f.usuario()))
                        .append(",\"a\":").append(Json.texto(f.accion())).append(",\"d\":").append(Json.texto(f.detalle())).append('}');
            }
            b.append("],\"acciones\":[");
            primero = true;
            for (String a : auditoriaDAO.acciones()) {
                if (!primero) b.append(',');
                primero = false;
                b.append(Json.texto(a));
            }
            return b.append("]}").toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo leer la auditoría");
            return "{\"filas\":[],\"acciones\":[]}";
        }
    }

    // ---------- Internos ----------

    private String preparar(Usuario yo, Path archivo, String nombre) {
        String motivo = Respaldo.solicitarRestauracion(archivo);
        if (motivo != null) {
            error(motivo);
            return FALLO;
        }
        AuditoriaDAO.registrar(yo, "Restauración solicitada", nombre);
        return "{\"ok\":true}";
    }

    private Usuario admin() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador() ? u : null;
    }

    private String denegado() {
        error("Solo el administrador puede hacer esto");
        return FALLO;
    }

    private static String rolValido(String rol) {
        return "Administrador".equals(rol) || "Cajero".equals(rol) ? rol : null;
    }

    /** Sin causa = el mensaje ya está pensado para mostrarse (por ejemplo "Ya existe un usuario con ese nombre"). */
    private String fallo(IllegalStateException e, String generico) {
        if (e.getCause() == null) {
            error(e.getMessage());
        } else {
            Registro.error(e);
            error(generico);
        }
        return FALLO;
    }

    private static Window ventana() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) return w;
        }
        return null;
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
