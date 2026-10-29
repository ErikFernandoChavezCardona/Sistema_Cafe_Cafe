package application.controller;

import application.model.dao.FacturaDAO;
import application.model.dao.ProveedorDAO;
import application.model.entity.ConfigFactura;
import application.model.entity.Factura;
import application.model.entity.FacturaCompra;
import application.model.entity.LineaFactura;
import application.model.entity.Proveedor;
import application.model.entity.Usuario;
import application.model.entity.VentaSinFactura;
import application.model.dao.AuditoriaDAO;
import application.util.Json;
import application.util.Registro;
import javafx.concurrent.Worker;
import javafx.print.PrinterJob;
import javafx.scene.web.WebEngine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Puente de las facturas. Se registra como window.javaFacturas.
 * - El cobro (VentaBridge.cobrar) ya emite la factura sola; aquí vive lo que viene después: verla, imprimirla,
 *   cambiar los datos del cliente (anula y emite otra), anular, configurar el CAI y las facturas de compra.
 * - Dinero en centavos; todo llega como texto.
 * - Solo el administrador entra a la pantalla Facturas; el cajero solo puede ver/imprimir/cambiar los datos
 *   de las facturas de SU turno abierto (para cuando el cliente pide RTN después de cobrar).
 */
public class FacturaBridge {

    private static final String FALLO = "{\"ok\":false}";
    private static final String OK = "{\"ok\":true}";

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final FacturaDAO dao = new FacturaDAO();
    private final ProveedorDAO proveedorDAO = new ProveedorDAO();
    private WebEngine impresion; // referencia fuerte mientras se imprime (si se pierde, el recolector rompe la impresión)

    public FacturaBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** La pantalla lo pregunta al cargar: si no es administrador, vuelve a Venta. */
    public boolean esAdministrador() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador();
    }

    /**
     * Estado del CAI y resumen de hoy, para la barra de la pantalla Facturas y el aviso del punto de venta:
     * {"cai":..,"desde":1,"hasta":500,"limite":"2027-05-14","sig":127,"pref":"000-001-01","numSig":"000-001-01-00000127",
     *  "isv":1500,"neg":..,"rtn":..,"dir":..,"tel":..,"mail":..,"auto":false,"rest":374,"dias":200,
     *  "problema":"" o el motivo por el que no se puede facturar,"hoyTotal":..,"hoyIsv":..,"hoyN":..,"sin":..}
     */
    public String estado() {
        try {
            ConfigFactura c = dao.config();
            String problema = c.problema(LocalDate.now().toString());
            long dias = 0;
            try {
                dias = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(c.limite()));
            } catch (Exception ignorada) {
                // sin fecha límite válida: se queda en 0 y "problema" ya lo explica
            }
            long[] h = dao.resumenHoy();
            return "{\"cai\":" + Json.texto(c.cai()) + ",\"desde\":" + c.desde() + ",\"hasta\":" + c.hasta()
                    + ",\"limite\":" + Json.texto(c.limite()) + ",\"sig\":" + c.siguiente()
                    + ",\"pref\":" + Json.texto(c.prefijo()) + ",\"numSig\":" + Json.texto(c.numero(c.siguiente()))
                    + ",\"isv\":" + c.isvX100() + ",\"neg\":" + Json.texto(c.negocio())
                    + ",\"rtn\":" + Json.texto(c.rtn()) + ",\"dir\":" + Json.texto(c.direccion())
                    + ",\"tel\":" + Json.texto(c.telefono()) + ",\"mail\":" + Json.texto(c.email())
                    + ",\"auto\":" + c.imprimirAuto() + ",\"rest\":" + c.restantes() + ",\"dias\":" + dias
                    + ",\"problema\":" + Json.texto(problema == null ? "" : problema)
                    + ",\"hoyTotal\":" + h[0] + ",\"hoyIsv\":" + h[1] + ",\"hoyN\":" + h[2] + ",\"sin\":" + h[3] + "}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo leer el estado de las facturas");
            return "{}";
        }
    }

    /** estado: "V" vigentes, "A" anuladas, vacío = todas. JSON: lista de facturas (sin productos). */
    public String listar(String estado, String texto) {
        if (!esAdministrador()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Factura f : dao.listar(estado, texto)) {
                if (!primero) b.append(',');
                primero = false;
                b.append(json(f, null));
            }
            return b.append(']').toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudieron leer las facturas");
            return "[]";
        }
    }

    /** Una factura completa (con productos y datos del negocio) lista para dibujar el comprobante, o "{}". */
    public String ver(String idFactura) {
        try {
            Factura f = dao.factura(entero(idFactura));
            if (f == null) return "{}";
            if (!esAdministrador() && !dao.esDelTurnoAbierto(f.id())) return "{}";
            return json(f, dao.config());
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo abrir la factura");
            return "{}";
        }
    }

    /** Ventas cobradas sin factura vigente (ventas anteriores a la facturación automática o facturas anuladas). */
    public String sinFactura() {
        if (!esAdministrador()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (VentaSinFactura v : dao.ventasSinFactura()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(v.id()).append(",\"com\":").append(Json.texto(v.comanda()))
                        .append(",\"pago\":").append(Json.texto(v.metodoPago()))
                        .append(",\"tot\":").append(v.totalCent()).append(",\"h\":").append(Json.texto(v.cobradaEn()))
                        .append(",\"l\":").append(lineas(v.lineas())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudieron leer las ventas sin factura");
            return "[]";
        }
    }

    /** Factura para una venta que quedó sin factura. Devuelve {"ok":true,"id":N} o {"ok":false}. */
    public String emitir(String idVenta, String nombre, String rtn) {
        try {
            Usuario u = web.usuarioActual();
            if (u == null) return sesionVencida();
            if (!u.esAdministrador()) throw new IllegalStateException("Solo el administrador puede hacer esto");
            int id = dao.emitirParaVenta(entero(idVenta), u.id(), nombre, rtn);
            return "{\"ok\":true,\"id\":" + id + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /**
     * El cliente pidió RTN o datos después de cobrar: anula la factura y emite otra con esos datos.
     * El cajero solo puede hacerlo con facturas de su turno abierto. Devuelve {"ok":true,"id":N} (id de la factura NUEVA).
     */
    public String reemitir(String idFactura, String nombre, String rtn) {
        try {
            Usuario u = web.usuarioActual();
            if (u == null) return sesionVencida();
            if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("Escribe el nombre del cliente");
            int id = dao.reemitir(entero(idFactura), u.id(), nombre, rtn, !u.esAdministrador());
            AuditoriaDAO.registrar(u, "Factura reemitida", "N.º " + entero(idFactura) + " → N.º " + id);
            return "{\"ok\":true,\"id\":" + id + "}";
        } catch (Exception e) {
            return fallo(e);
        }
    }

    public String anular(String idFactura, String motivo) {
        try {
            Usuario u = web.usuarioActual();
            if (u == null) return sesionVencida();
            if (!u.esAdministrador()) throw new IllegalStateException("Solo el administrador puede anular facturas");
            dao.anular(entero(idFactura), u.id(), motivo);
            AuditoriaDAO.registrar(u, "Factura anulada", "N.º " + entero(idFactura) + " · " + (motivo == null ? "" : motivo.trim()));
            return OK;
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /** ISV llega como texto ("15" o "15.5"). imprimirAuto: "1" o "0". */
    public String guardarConfig(String cai, String desde, String hasta, String limite, String siguiente,
                                String prefijo, String negocio, String rtn, String direccion, String telefono,
                                String email, String isv, String imprimirAuto) {
        try {
            if (!esAdministrador()) throw new IllegalStateException("Solo el administrador puede cambiar la configuración");
            int isvX100 = (int) Math.round(Double.parseDouble(isv.trim().replace(',', '.')) * 100);
            dao.guardarConfig(new ConfigFactura(txt(cai), entero(desde), entero(hasta), txt(limite), entero(siguiente),
                    txt(prefijo), isvX100, txt(negocio), txt(rtn).replaceAll("\\D", ""), txt(direccion), txt(telefono),
                    txt(email), "1".equals(imprimirAuto)));
            AuditoriaDAO.registrar(web.usuarioActual(), "Configuración de facturas", "CAI " + txt(cai) + " · rango " + entero(desde)
                    + "-" + entero(hasta) + " · siguiente " + entero(siguiente) + " · ISV " + isv.trim() + "%");
            return OK;
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /** [{"id":3,"pv":"Lácteos","nu":"001-002-01-00004512","fe":"2026-10-25","no":"Leche","to":320000}, ...] */
    public String compras() {
        if (!esAdministrador()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (FacturaCompra f : dao.compras()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(f.id()).append(",\"pv\":").append(Json.texto(f.proveedor()))
                        .append(",\"nu\":").append(Json.texto(f.numero())).append(",\"fe\":").append(Json.texto(f.fecha()))
                        .append(",\"no\":").append(Json.texto(f.nota())).append(",\"to\":").append(f.totalCent()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudieron leer las facturas de compra");
            return "[]";
        }
    }

    /** Proveedores activos para elegir al registrar una factura de compra: [{"id":1,"n":"Lácteos"}, ...] */
    public String proveedores() {
        if (!esAdministrador()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Proveedor p : proveedorDAO.todos()) {
                if (!p.activo()) continue;
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(p.id()).append(",\"n\":").append(Json.texto(p.nombre())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudieron leer los proveedores");
            return "[]";
        }
    }

    /** El total llega en centavos. */
    public String registrarCompra(String idProveedor, String numero, String fecha, String totalCentavos, String nota) {
        try {
            if (!esAdministrador()) throw new IllegalStateException("Solo el administrador puede hacer esto");
            dao.registrarCompra(entero(idProveedor), numero, fecha, entero(totalCentavos), nota);
            return OK;
        } catch (Exception e) {
            return fallo(e);
        }
    }

    /**
     * Imprime el comprobante en la impresora predeterminada de Windows, sin abrir ningún cuadro.
     * Recibe el documento HTML completo ya armado por la pantalla (assets/js/factura_ticket.js), así lo impreso
     * es idéntico a lo que se ve en pantalla.
     */
    public String imprimir(String html) {
        try {
            if (web.usuarioActual() == null) return sesionVencida();
            if (html == null || html.isBlank() || html.length() > 200_000)
                throw new IllegalArgumentException("El comprobante no es válido");
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) throw new IllegalStateException("No hay una impresora configurada en Windows");
            WebEngine motor = new WebEngine(); // oculto: solo existe para imprimir
            motor.setJavaScriptEnabled(false);
            impresion = motor;
            motor.getLoadWorker().stateProperty().addListener((obs, anterior, estado) -> {
                if (estado == Worker.State.SUCCEEDED) {
                    try {
                        motor.print(job);
                        job.endJob();
                    } catch (Exception e) {
                        Registro.error(e);
                        job.cancelJob();
                        error("No se pudo imprimir");
                    } finally {
                        impresion = null;
                    }
                } else if (estado == Worker.State.FAILED || estado == Worker.State.CANCELLED) {
                    job.cancelJob();
                    impresion = null;
                    error("No se pudo preparar el comprobante para imprimir");
                }
            });
            motor.loadContent(html);
            return OK;
        } catch (Exception e) {
            return fallo(e);
        }
    }

    // ---------- interno ----------

    private static String json(Factura f, ConfigFactura negocio) {
        StringBuilder b = new StringBuilder("{\"id\":").append(f.id()).append(",\"venta\":").append(f.idVenta())
                .append(",\"num\":").append(Json.texto(f.numero())).append(",\"cai\":").append(Json.texto(f.cai()))
                .append(",\"desde\":").append(Json.texto(f.rangoDesde())).append(",\"hasta\":").append(Json.texto(f.rangoHasta()))
                .append(",\"limite\":").append(Json.texto(f.fechaLimite())).append(",\"rtn\":").append(Json.texto(f.rtn()))
                .append(",\"cli\":").append(Json.texto(f.cliente())).append(",\"sub\":").append(f.subtotalCent())
                .append(",\"isv\":").append(f.impuestoCent()).append(",\"pct\":").append(f.impuestoPctX100())
                .append(",\"tot\":").append(f.totalCent()).append(",\"user\":").append(Json.texto(f.atendio()))
                .append(",\"fecha\":").append(Json.texto(f.emitidaEn())).append(",\"an\":").append(f.anulada())
                .append(",\"mo\":").append(Json.texto(f.motivo())).append(",\"pago\":").append(Json.texto(f.metodoPago()))
                .append(",\"rec\":").append(f.recibidoCent()).append(",\"cam\":").append(f.cambioCent())
                .append(",\"com\":").append(Json.texto(f.comanda())).append(",\"l\":").append(lineas(f.lineas()));
        if (negocio != null) {
            b.append(",\"neg\":{\"n\":").append(Json.texto(negocio.negocio())).append(",\"rtn\":").append(Json.texto(negocio.rtn()))
                    .append(",\"dir\":").append(Json.texto(negocio.direccion())).append(",\"tel\":").append(Json.texto(negocio.telefono()))
                    .append(",\"mail\":").append(Json.texto(negocio.email())).append(",\"auto\":").append(negocio.imprimirAuto()).append('}');
        }
        return b.append('}').toString();
    }

    private static String lineas(List<LineaFactura> lista) {
        StringBuilder b = new StringBuilder("[");
        boolean primero = true;
        for (LineaFactura l : lista) {
            if (!primero) b.append(',');
            primero = false;
            b.append("{\"n\":").append(Json.texto(l.producto())).append(",\"q\":").append(l.cantidad())
                    .append(",\"p\":").append(l.precioCent()).append(",\"s\":").append(l.subtotalCent()).append('}');
        }
        return b.append(']').toString();
    }

    private static String txt(String s) {
        return s == null ? "" : s.trim();
    }

    private static int entero(String texto) {
        if (texto == null || texto.isBlank()) return 0;
        return Integer.parseInt(texto.trim());
    }

    private String sesionVencida() {
        error("Inicia sesión de nuevo para continuar");
        return FALLO;
    }

    /** Los mensajes de las excepciones propias ya están en español y son seguros de mostrar. */
    private String fallo(Exception e) {
        Registro.error(e);
        if (e instanceof NumberFormatException) error("Revisa los números: alguno no es válido");
        else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) error(e.getMessage());
        else error("No se pudo completar la operación");
        return FALLO;
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
