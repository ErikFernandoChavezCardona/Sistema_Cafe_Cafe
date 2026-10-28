package application.controller;

import application.model.dao.VentaDAO;
import application.model.entity.Comanda;
import application.model.entity.LineaVenta;
import application.model.entity.ResultadoCobro;
import application.model.entity.Usuario;
import application.util.Json;
import javafx.scene.web.WebEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Puente del cobro y las comandas pendientes. Se registra como window.javaVenta.
 * Dinero: centavos enteros, y las cantidades llegan como texto.
 * Las líneas llegan como texto "idProducto:cantidad,idProducto:cantidad" (ejemplo: "12:2,35:1").
 */
public class VentaBridge {

    private static final String FALLO = "{\"ok\":false}";

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final VentaDAO dao = new VentaDAO();

    public VentaBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** JSON: [{"id":3,"n":"Mesa 2","t":24500,"h":"2026-10-25 12:40:00"}, ...] */
    public String pendientes() {
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Comanda c : dao.pendientes()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(c.id()).append(",\"n\":").append(Json.texto(c.nombre()))
                        .append(",\"t\":").append(c.totalCent()).append(",\"h\":").append(Json.texto(c.creadaEn())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudieron leer las comandas pendientes");
            return "[]";
        }
    }

    /** JSON: {"id":3,"nombre":"Mesa 2","lineas":[{"id":12,"q":2}]} o "{}" si ya no está pendiente. */
    public String abrirPendiente(String idVenta) {
        try {
            Comanda c = dao.pendiente(Integer.parseInt(idVenta.trim()));
            if (c == null) return "{}";
            StringBuilder b = new StringBuilder("{\"id\":").append(c.id())
                    .append(",\"nombre\":").append(Json.texto(c.nombre())).append(",\"lineas\":[");
            boolean primero = true;
            for (LineaVenta l : c.lineas()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(l.idProducto()).append(",\"q\":").append(l.cantidad()).append('}');
            }
            return b.append("]}").toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo abrir la comanda");
            return "{}";
        }
    }

    /** idVenta "0" o vacío = comanda nueva. Devuelve {"ok":true,"id":N} o {"ok":false}. */
    public String guardarPendiente(String idVenta, String nombre, String lineas) {
        try {
            Usuario u = web.usuarioActual();
            if (u == null) return sesionVencida();
            int id = dao.guardarPendiente(entero(idVenta), u.id(), nombre, leerLineas(lineas));
            return "{\"ok\":true,\"id\":" + id + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /**
     * Cobra la venta. metodo: "Efectivo", "Tarjeta" o "Transferencia". recibidoCentavos solo importa en efectivo.
     * La factura se emite en el mismo instante: clienteNombre y clienteRtn vacíos = Consumidor final.
     * Devuelve {"ok":true,"id":N,"total":..,"recibido":..,"cambio":..,"factura":N,"numero":"000-001-01-00000001"} o {"ok":false}.
     */
    public String cobrar(String idVenta, String nombre, String lineas, String metodo, String recibidoCentavos,
                         String clienteNombre, String clienteRtn) {
        try {
            Usuario u = web.usuarioActual();
            if (u == null) return sesionVencida();
            int recibido = (recibidoCentavos == null || recibidoCentavos.isBlank()) ? 0 : entero(recibidoCentavos);
            ResultadoCobro r = dao.cobrar(entero(idVenta), u.id(), nombre, leerLineas(lineas), metodo, recibido,
                    clienteNombre, clienteRtn);
            return "{\"ok\":true,\"id\":" + r.idVenta() + ",\"total\":" + r.totalCent()
                    + ",\"recibido\":" + r.recibidoCent() + ",\"cambio\":" + r.cambioCent()
                    + ",\"factura\":" + r.idFactura() + ",\"numero\":" + Json.texto(r.numeroFactura()) + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    public String cancelarPendiente(String idVenta) {
        try {
            dao.cancelarPendiente(entero(idVenta));
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    // ---------- interno ----------

    private static int entero(String texto) {
        if (texto == null || texto.isBlank()) return 0;
        return Integer.parseInt(texto.trim());
    }

    private static List<LineaVenta> leerLineas(String texto) {
        List<LineaVenta> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) return out;
        for (String par : texto.split(",")) {
            String[] p = par.trim().split(":");
            if (p.length != 2) throw new IllegalArgumentException("La comanda tiene datos inválidos");
            int id = Integer.parseInt(p[0].trim());
            int cantidad = Integer.parseInt(p[1].trim());
            if (id <= 0 || cantidad <= 0 || cantidad > 99) throw new IllegalArgumentException("Cantidad inválida");
            out.add(new LineaVenta(id, cantidad));
        }
        return out;
    }

    private String sesionVencida() {
        error("Inicia sesión de nuevo para vender");
        return FALLO;
    }

    /** Los mensajes de las excepciones propias ya están en español y son seguros de mostrar. */
    private String fallo(Exception e) {
        e.printStackTrace();
        if (e instanceof NumberFormatException) error("Los datos de la venta no son válidos");
        else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) error(e.getMessage());
        else error("No se pudo completar la operación");
        return FALLO;
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
