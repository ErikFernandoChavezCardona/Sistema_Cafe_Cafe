package application.controller;

import application.model.dao.ReporteDAO;
import application.model.dao.ReporteDAO.Dia;
import application.model.dao.ReporteDAO.FilaLibro;
import application.model.dao.ReporteDAO.FilaProducto;
import application.model.dao.ReporteDAO.FilaTurno;
import application.model.dao.ReporteDAO.Resumen;
import application.model.entity.Usuario;
import application.util.ExcelTabla;
import application.util.ExcelTabla.Col;
import application.util.ExcelTabla.Tipo;
import application.util.Json;
import application.util.Registro;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Puente de la pantalla Reportes (reportes.html). Se registra como window.javaReportes.
 * Los reportes SOLO LEEN datos: no modifican nada. Solo el administrador entra a esta pantalla.
 * Dinero en centavos; las fechas del período llegan como texto AAAA-MM-DD. Cada pestaña tiene su método:
 * resumen, productos, turnos y libro (devuelven JSON), y exportarExcel guarda la pestaña que se está viendo.
 */
public class ReporteBridge {

    private static final Pattern FECHA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final long MAX_DIAS = 366;
    private static final String VACIO = "[]";
    private static final String VACIO_RESUMEN = "{\"dias\":[],\"horas\":[],\"top\":[],\"bajos\":[],\"ctl\":{\"cerrados\":0,\"conDif\":0,\"dif\":0,\"anuladas\":0}}";

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final ReporteDAO dao = new ReporteDAO();

    public ReporteBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** La pantalla lo pregunta al cargar: si no es administrador, vuelve a Venta. */
    public boolean esAdministrador() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador();
    }

    /**
     * Pestaña Resumen: {"dias":[{"f","n","v","ef","tj","tr","eg"}],"horas":[{"h":8,"m":..}],"top":[{"n","q","m"}],
     * "bajos":[{"n","u","s","mi"}],"ctl":{"cerrados","conDif","dif","anuladas"}}.
     * dias trae TODOS los días del período (con ceros); n = ventas cobradas, v = total vendido, ef/tj/tr = por método de pago, eg = egresos.
     */
    public String resumen(String desde, String hasta) {
        if (!permitido()) return VACIO_RESUMEN;
        try {
            LocalDate[] r = leerRango(desde, hasta);
            if (r == null) return VACIO_RESUMEN;
            Resumen x = dao.resumen(r[0], r[1]);
            StringBuilder b = new StringBuilder("{\"dias\":[");
            for (int i = 0; i < x.dias().size(); i++) {
                Dia d = x.dias().get(i);
                if (i > 0) b.append(',');
                b.append("{\"f\":").append(Json.texto(d.fecha())).append(",\"n\":").append(d.ventas()).append(",\"v\":").append(d.total())
                        .append(",\"ef\":").append(d.efectivo()).append(",\"tj\":").append(d.tarjeta())
                        .append(",\"tr\":").append(d.transferencia()).append(",\"eg\":").append(d.egresos()).append('}');
            }
            b.append("],\"horas\":[");
            for (int i = 0; i < x.horas().size(); i++) {
                if (i > 0) b.append(',');
                b.append("{\"h\":").append(x.horas().get(i).desde()).append(",\"m\":").append(x.horas().get(i).total()).append('}');
            }
            b.append("],\"top\":[");
            for (int i = 0; i < x.top().size(); i++) {
                if (i > 0) b.append(',');
                b.append("{\"n\":").append(Json.texto(x.top().get(i).nombre())).append(",\"q\":").append(x.top().get(i).cantidad())
                        .append(",\"m\":").append(x.top().get(i).monto()).append('}');
            }
            b.append("],\"bajos\":[");
            for (int i = 0; i < x.bajos().size(); i++) {
                if (i > 0) b.append(',');
                b.append("{\"n\":").append(Json.texto(x.bajos().get(i).nombre())).append(",\"u\":").append(Json.texto(x.bajos().get(i).unidad()))
                        .append(",\"s\":").append(x.bajos().get(i).stock()).append(",\"mi\":").append(x.bajos().get(i).minimo()).append('}');
            }
            return b.append("],\"ctl\":{\"cerrados\":").append(x.turnosCerrados()).append(",\"conDif\":").append(x.turnosConDiferencia())
                    .append(",\"dif\":").append(x.diferenciaCent()).append(",\"anuladas\":").append(x.facturasAnuladas()).append("}}").toString();
        } catch (Exception e) {
            falla(e, "No se pudo leer el resumen");
            return VACIO_RESUMEN;
        }
    }

    /** Pestaña Productos: [{"n","c" (categoría),"q" (cantidad),"m" (vendido),"co" (costo estimado o null si no se puede calcular)}]. */
    public String productos(String desde, String hasta) {
        if (!permitido()) return VACIO;
        try {
            LocalDate[] r = leerRango(desde, hasta);
            if (r == null) return VACIO;
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (FilaProducto p : dao.productos(r[0], r[1])) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"n\":").append(Json.texto(p.nombre())).append(",\"c\":").append(Json.texto(p.categoria()))
                        .append(",\"q\":").append(p.cantidad()).append(",\"m\":").append(p.montoCent())
                        .append(",\"co\":").append(p.costoCent()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer los productos");
            return VACIO;
        }
    }

    /** Pestaña Turnos de caja: [{"id","ap","cj","ci","es","co","di","st"}]; es, co y di vienen null si el turno sigue abierto. */
    public String turnos(String desde, String hasta) {
        if (!permitido()) return VACIO;
        try {
            LocalDate[] r = leerRango(desde, hasta);
            if (r == null) return VACIO;
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (FilaTurno t : dao.turnos(r[0], r[1])) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(t.id()).append(",\"ap\":").append(Json.texto(t.apertura()))
                        .append(",\"cj\":").append(Json.texto(t.cajero())).append(",\"ci\":").append(Json.texto(t.cierre()))
                        .append(",\"es\":").append(t.esperadoCent()).append(",\"co\":").append(t.contadoCent())
                        .append(",\"di\":").append(t.diferenciaCent()).append(",\"st\":").append(Json.texto(t.estado())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer los turnos de caja");
            return VACIO;
        }
    }

    /** Pestaña Libro de ventas (ISV): [{"f" (fecha y hora),"d" (número de factura),"c" (cliente),"r" (RTN),"x" (anulada),"g" (gravado),"i" (ISV),"t" (total)}], una por factura. */
    public String libro(String desde, String hasta) {
        if (!permitido()) return VACIO;
        try {
            LocalDate[] r = leerRango(desde, hasta);
            if (r == null) return VACIO;
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (FilaLibro l : dao.libro(r[0], r[1])) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"f\":").append(Json.texto(l.emitida())).append(",\"d\":").append(Json.texto(l.numero()))
                        .append(",\"c\":").append(Json.texto(l.cliente())).append(",\"r\":").append(Json.texto(l.rtn() == null ? "" : l.rtn()))
                        .append(",\"x\":").append(l.anulada()).append(",\"g\":").append(l.gravadoCent())
                        .append(",\"i\":").append(l.isvCent()).append(",\"t\":").append(l.totalCent()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudo leer el libro de ventas");
            return VACIO;
        }
    }

    /**
     * Guarda en un archivo de Excel (.xlsx, con tabla) la pestaña que se está viendo: "res", "pro", "tur" o "lib".
     * El administrador elige dónde guardarlo. categoria solo se usa en "pro" (vacío = todas).
     */
    public void exportarExcel(String desde, String hasta, String pestana, String categoria) {
        if (!permitido()) return;
        try {
            LocalDate[] r = leerRango(desde, hasta);
            if (r == null) return;
            String sub = "Período: " + r[0] + " al " + r[1];
            String hoja, titulo, archivo;
            List<Col> cols = new ArrayList<>();
            List<Object[]> filas = new ArrayList<>();
            switch (pestana == null ? "" : pestana) {
                case "res" -> {
                    hoja = "Ventas por día";
                    titulo = "Café Café · Ventas por día";
                    archivo = "ventas_por_dia";
                    cols.add(new Col("Fecha", 14, Tipo.FECHA));
                    cols.add(new Col("Ventas (n.º)", 13, Tipo.ENTERO, true));
                    cols.add(new Col("Total vendido", 16, Tipo.DINERO, true));
                    cols.add(new Col("Efectivo", 16, Tipo.DINERO, true));
                    cols.add(new Col("Tarjeta", 16, Tipo.DINERO, true));
                    cols.add(new Col("Transferencia", 16, Tipo.DINERO, true));
                    cols.add(new Col("Egresos", 16, Tipo.DINERO, true));
                    cols.add(new Col("Utilidad estimada", 18, Tipo.DINERO, true));
                    for (Dia d : dao.resumen(r[0], r[1]).dias()) {
                        filas.add(new Object[]{d.fecha(), d.ventas(), d.total(), d.efectivo(), d.tarjeta(), d.transferencia(),
                                d.egresos(), d.total() - d.egresos()});
                    }
                }
                case "pro" -> {
                    hoja = "Productos vendidos";
                    titulo = "Café Café · Productos vendidos";
                    archivo = "productos_vendidos";
                    if (categoria != null && !categoria.isBlank()) sub += " · Categoría: " + categoria.trim();
                    cols.add(new Col("Producto", 32, Tipo.TEXTO));
                    cols.add(new Col("Categoría", 22, Tipo.TEXTO));
                    cols.add(new Col("Cantidad", 12, Tipo.ENTERO, true));
                    cols.add(new Col("Ventas", 16, Tipo.DINERO, true));
                    cols.add(new Col("Costo estimado", 16, Tipo.DINERO));
                    cols.add(new Col("Margen (L)", 16, Tipo.DINERO));
                    cols.add(new Col("Margen %", 12, Tipo.PORCENTAJE));
                    for (FilaProducto p : dao.productos(r[0], r[1])) {
                        if (categoria != null && !categoria.isBlank() && !categoria.trim().equals(p.categoria())) continue;
                        Long margen = p.costoCent() == null ? null : p.montoCent() - p.costoCent();
                        Double pct = margen == null || p.montoCent() == 0 ? null : margen / (double) p.montoCent();
                        filas.add(new Object[]{p.nombre(), p.categoria(), p.cantidad(), p.montoCent(), p.costoCent(), margen, pct});
                    }
                }
                case "tur" -> {
                    hoja = "Turnos de caja";
                    titulo = "Café Café · Turnos de caja";
                    archivo = "turnos_de_caja";
                    cols.add(new Col("Fecha", 14, Tipo.FECHA));
                    cols.add(new Col("Cajero", 20, Tipo.TEXTO));
                    cols.add(new Col("Apertura", 18, Tipo.FECHAHORA));
                    cols.add(new Col("Cierre", 18, Tipo.FECHAHORA));
                    cols.add(new Col("Esperado", 16, Tipo.DINERO));
                    cols.add(new Col("Contado", 16, Tipo.DINERO));
                    cols.add(new Col("Diferencia", 16, Tipo.DINERO, true));
                    cols.add(new Col("Estado", 16, Tipo.TEXTO));
                    for (FilaTurno t : dao.turnos(r[0], r[1])) {
                        String estado = "Abierta".equals(t.estado()) ? "Abierta"
                                : (t.diferenciaCent() != null && t.diferenciaCent() != 0 ? "Con diferencia" : "Cuadrado");
                        filas.add(new Object[]{t.apertura().substring(0, 10), t.cajero(), t.apertura(), t.cierre(),
                                t.esperadoCent(), t.contadoCent(), t.diferenciaCent(), estado});
                    }
                }
                case "lib" -> {
                    hoja = "Libro de ventas";
                    titulo = "Café Café · Libro de ventas (ISV)";
                    archivo = "libro_de_ventas";
                    cols.add(new Col("Fecha y hora", 20, Tipo.FECHAHORA));
                    cols.add(new Col("N.º de factura", 24, Tipo.TEXTO));
                    cols.add(new Col("Cliente", 28, Tipo.TEXTO));
                    cols.add(new Col("RTN", 18, Tipo.TEXTO));
                    cols.add(new Col("Estado", 12, Tipo.TEXTO));
                    cols.add(new Col("Gravado", 16, Tipo.DINERO, true));
                    cols.add(new Col("ISV", 16, Tipo.DINERO, true));
                    cols.add(new Col("Total", 16, Tipo.DINERO, true));
                    int vigentes = 0, anuladas = 0;
                    for (FilaLibro l : dao.libro(r[0], r[1])) {
                        if (l.anulada()) anuladas++; else vigentes++;
                        filas.add(new Object[]{l.emitida(), l.numero(), l.cliente(), l.rtn(), l.anulada() ? "Anulada" : "Vigente",
                                l.gravadoCent(), l.isvCent(), l.totalCent()});
                    }
                    sub += " · " + vigentes + " facturas vigentes, " + anuladas + " anuladas (las anuladas no suman)";
                }
                default -> {
                    error("Elige qué reporte exportar");
                    return;
                }
            }
            if (filas.isEmpty()) {
                error("No hay datos para exportar en este período");
                return;
            }
            byte[] contenido = ExcelTabla.generar(hoja, titulo, sub, cols, filas);
            String nombre = archivo + "_" + r[0] + "_a_" + r[1] + ".xlsx";
            Platform.runLater(() -> {
                try {
                    FileChooser fc = new FileChooser();
                    fc.setTitle("Guardar reporte");
                    fc.setInitialFileName(nombre);
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Libro de Excel", "*.xlsx"));
                    File destino = fc.showSaveDialog(null);
                    if (destino == null) return; // el administrador canceló
                    Files.write(destino.toPath(), contenido);
                    avisar("Archivo guardado: " + destino.getName());
                } catch (Exception e) {
                    falla(e, "No se pudo guardar el archivo (¿está abierto en Excel?)");
                }
            });
        } catch (Exception e) {
            falla(e, "No se pudo exportar");
        }
    }

    // ---------- Apoyo ----------

    /** Valida el período que llega como texto. Devuelve {desde, hasta} o null (y avisa) si algo no es válido. */
    private LocalDate[] leerRango(String desde, String hasta) {
        try {
            if (desde == null || hasta == null || !FECHA.matcher(desde.trim()).matches() || !FECHA.matcher(hasta.trim()).matches()) {
                error("Las fechas deben tener el formato AAAA-MM-DD");
                return null;
            }
            LocalDate d = LocalDate.parse(desde.trim());
            LocalDate h = LocalDate.parse(hasta.trim());
            if (d.isAfter(h)) {
                error("La fecha inicial no puede ser mayor que la final");
                return null;
            }
            if (ChronoUnit.DAYS.between(d, h) >= MAX_DIAS) {
                error("Elige un período de máximo un año");
                return null;
            }
            return new LocalDate[]{d, h};
        } catch (DateTimeParseException e) {
            error("Alguna de las fechas no existe");
            return null;
        }
    }

    /** Solo el administrador usa esta pantalla. */
    private boolean permitido() {
        if (esAdministrador()) return true;
        error("Solo el administrador puede ver los reportes");
        return false;
    }

    /** Sin "causa" = mensaje pensado para el administrador; con causa = error técnico, se muestra uno genérico. */
    private void falla(Exception e, String generico) {
        Registro.error(e);
        error(e instanceof IllegalStateException && e.getCause() == null ? e.getMessage() : generico);
    }

    private void avisar(String mensaje) {
        engine.executeScript("window.aviso && window.aviso(" + Json.texto(mensaje) + ")");
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
