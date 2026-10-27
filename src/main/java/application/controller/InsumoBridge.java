package application.controller;

import application.model.dao.InventarioDAO;
import application.model.dao.ProveedorDAO;
import application.model.entity.InsumoAdmin;
import application.model.entity.MovimientoInsumo;
import application.model.entity.Proveedor;
import application.model.entity.Usuario;
import application.util.Json;
import javafx.scene.web.WebEngine;

/**
 * Puente de las pantallas Insumos (insumos.html) e Insumo (insumo.html). Se registra como window.javaInsumos.
 * Solo el administrador ve y cambia el inventario. Dinero en centavos y cantidades como texto.
 * Las cantidades de stock viajan SIEMPRE en la unidad base del insumo: g, ml o ud (1 kg = 1000 g).
 */
public class InsumoBridge {

    private static final String FALLO = "{\"ok\":false}";

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión y para cambiar de página
    private final InventarioDAO dao = new InventarioDAO();
    private final ProveedorDAO proveedorDAO = new ProveedorDAO();
    private int seleccionado = 0; // insumo que muestra insumo.html (0 = uno nuevo)

    public InsumoBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** Las pantallas lo preguntan al cargar: si no es administrador, vuelven a Venta. */
    public boolean esAdministrador() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador();
    }

    // ---------- Lista ----------

    /** JSON: id, n (nombre), u (g/ml/ud), s (stock), m (mínimo), c (costo por 1000 unidades base), pid, p (proveedor), a (activo), uso, mov. */
    public String lista() {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (InsumoAdmin i : dao.todos()) {
                if (!primero) b.append(',');
                primero = false;
                b.append(insumoJson(i));
            }
            return b.append(']').toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudieron leer los insumos");
            return "[]";
        }
    }

    /** JSON: [{"id":1,"n":"Lácteos","t":"9988-1234","r":"0801-...","a":true,"ni":2}] */
    public String proveedores() {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Proveedor p : proveedorDAO.todos()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(p.id()).append(",\"n\":").append(Json.texto(p.nombre()))
                        .append(",\"t\":").append(Json.texto(p.telefono())).append(",\"r\":").append(Json.texto(p.rtn()))
                        .append(",\"a\":").append(p.activo()).append(",\"ni\":").append(p.insumos()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudieron leer los proveedores");
            return "[]";
        }
    }

    public String guardarProveedor(String id, String nombre, String telefono, String rtn) {
        if (!permitido()) return FALLO;
        try {
            if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del proveedor");
            int nuevo = proveedorDAO.guardar(entero(id), nombre.trim(), limpio(telefono), limpio(rtn));
            return "{\"ok\":true,\"id\":" + nuevo + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    public String activarProveedor(String id, String activo) {
        if (!permitido()) return FALLO;
        try {
            proveedorDAO.cambiarActivo(entero(id), "1".equals(activo));
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    // ---------- Detalle de un insumo ----------

    /** Abre la pantalla de detalle. id "0" = insumo nuevo. */
    public void abrirInsumo(String id) {
        if (!permitido()) return;
        try {
            seleccionado = entero(id);
            web.irA("insumo.html");
        } catch (Exception e) {
            fallo(e);
        }
    }

    /** El insumo seleccionado en JSON (o uno vacío si es nuevo, o "{}" si ya no existe). */
    public String insumoActual() {
        if (!permitido()) return "{}";
        try {
            if (seleccionado == 0) return "{\"id\":0,\"n\":\"\",\"u\":\"g\",\"s\":0,\"m\":0,\"c\":0,\"pid\":0,\"p\":\"\",\"a\":true,\"uso\":0,\"mov\":0}";
            InsumoAdmin i = dao.porId(seleccionado);
            return i == null ? "{}" : insumoJson(i);
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo leer el insumo");
            return "{}";
        }
    }

    /** Historial del insumo seleccionado: [{"f":"2026-10-26 10:42:00","t":"Compra","q":20000,"nota":"..."}] (q con signo, en unidad base). */
    public String movimientos() {
        if (!permitido() || seleccionado == 0) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (MovimientoInsumo m : dao.movimientos(seleccionado, 60)) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"f\":").append(Json.texto(m.fecha())).append(",\"t\":").append(Json.texto(m.tipo()))
                        .append(",\"q\":").append(m.cantidad()).append(",\"nota\":").append(Json.texto(m.nota())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo leer el historial");
            return "[]";
        }
    }

    /** idInsumo "0" = nuevo. costoX1000 y minimoBase llegan como enteros en texto. Devuelve {"ok":true,"id":N}. */
    public String guardarInsumo(String id, String nombre, String unidad, String costoX1000, String minimoBase,
                                String idProveedor, String activo) {
        if (!permitido()) return FALLO;
        try {
            if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del insumo");
            if (nombre.trim().length() > 60) throw new IllegalArgumentException("El nombre es demasiado largo");
            if (!"g".equals(unidad) && !"ml".equals(unidad) && !"ud".equals(unidad))
                throw new IllegalArgumentException("Elige la unidad del insumo");
            int costo = entero(costoX1000);
            int minimo = entero(minimoBase);
            if (costo < 0 || minimo < 0) throw new IllegalArgumentException("El costo y el stock mínimo no pueden ser negativos");
            int nuevo = dao.guardar(entero(id), nombre.trim(), unidad, costo, minimo, entero(idProveedor), "1".equals(activo));
            seleccionado = nuevo;
            return "{\"ok\":true,\"id\":" + nuevo + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /** cantidadBase en g/ml/ud, totalCentavos lo pagado. actualizarCosto y pagadoCaja: "1" o "0". */
    public String registrarCompra(String id, String cantidadBase, String totalCentavos, String actualizarCosto, String pagadoCaja) {
        if (!permitido()) return FALLO;
        try {
            int cantidad = entero(cantidadBase);
            int total = entero(totalCentavos);
            if (cantidad <= 0) throw new IllegalArgumentException("La cantidad comprada debe ser mayor que cero");
            if (total <= 0) throw new IllegalArgumentException("El costo total debe ser mayor que cero");
            dao.registrarCompra(entero(id), cantidad, total, "1".equals(actualizarCosto), "1".equals(pagadoCaja));
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /** modo "conteo" (cantidadBase = stock real contado) o "merma" (cantidadBase = lo perdido). El motivo es obligatorio. */
    public String ajustar(String id, String modo, String cantidadBase, String motivo) {
        if (!permitido()) return FALLO;
        try {
            if (motivo == null || motivo.isBlank()) throw new IllegalArgumentException("Escribe el motivo del ajuste");
            int cantidad = entero(cantidadBase);
            if (cantidad < 0 || ("merma".equals(modo) && cantidad == 0)) throw new IllegalArgumentException("Escribe una cantidad válida");
            dao.ajustar(entero(id), modo, cantidad, motivo.trim());
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    // ---------- interno ----------

    private static String insumoJson(InsumoAdmin i) {
        return "{\"id\":" + i.id() + ",\"n\":" + Json.texto(i.nombre()) + ",\"u\":" + Json.texto(i.unidad())
                + ",\"s\":" + i.stock() + ",\"m\":" + i.stockMin() + ",\"c\":" + i.costoX1000()
                + ",\"pid\":" + i.idProveedor() + ",\"p\":" + Json.texto(i.proveedor()) + ",\"a\":" + i.activo()
                + ",\"uso\":" + i.usadoEn() + ",\"mov\":" + i.movimientos() + "}";
    }

    private boolean permitido() {
        if (esAdministrador()) return true;
        error("Solo el administrador puede ver y cambiar el inventario");
        return false;
    }

    private static int entero(String texto) {
        if (texto == null || texto.isBlank()) return 0;
        return Integer.parseInt(texto.trim());
    }

    private static String limpio(String texto) {
        return texto == null ? "" : texto.trim();
    }

    /** Los mensajes de las excepciones propias ya están en español y son seguros de mostrar. */
    private String fallo(Exception e) {
        e.printStackTrace();
        if (e instanceof NumberFormatException) error("Los datos no son válidos");
        else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) error(e.getMessage());
        else error("No se pudo completar la operación");
        return FALLO;
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
