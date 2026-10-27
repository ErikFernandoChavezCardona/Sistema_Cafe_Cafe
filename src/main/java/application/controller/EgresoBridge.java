package application.controller;

import application.model.dao.EgresoDAO;
import application.model.dao.TurnoDAO;
import application.model.entity.EgresoFila;
import application.model.entity.FiltroEgresos;
import application.model.entity.MontoCategoria;
import application.model.entity.ResumenEgresos;
import application.model.entity.Turno;
import application.model.entity.Usuario;
import application.util.Json;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Puente de la pantalla Egresos (egresos.html). Se registra como window.javaEgresos.
 * Solo el administrador entra a esta pantalla (el cajero sigue anotando sus gastos desde Caja).
 * Dinero en centavos; las cantidades y filtros llegan como texto.
 */
public class EgresoBridge {

    private static final String FALLO = "{\"ok\":false}";
    private static final Pattern FECHA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final EgresoDAO egresoDAO = new EgresoDAO();
    private final TurnoDAO turnoDAO = new TurnoDAO();

    public EgresoBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** La pantalla lo pregunta al cargar: si no es administrador, vuelve a Venta. */
    public boolean esAdministrador() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador();
    }

    /** {"fijas":[...],"otras":[...]}: las fijas son para registrar; las otras (egresos viejos) solo para filtrar. */
    public String categorias() {
        if (!permitido()) return "{\"fijas\":[],\"otras\":[]}";
        try {
            return "{\"fijas\":" + lista(EgresoDAO.CATEGORIAS) + ",\"otras\":" + lista(egresoDAO.categoriasViejas()) + "}";
        } catch (Exception e) {
            falla(e, "No se pudieron leer las categorías");
            return "{\"fijas\":[],\"otras\":[]}";
        }
    }

    /** {"abierta":true,"turno":14}: para saber si se puede pagar con efectivo de la caja. */
    public String estadoCaja() {
        if (!permitido()) return "{\"abierta\":false,\"turno\":0}";
        try {
            Turno t = turnoDAO.abierto();
            return t == null ? "{\"abierta\":false,\"turno\":0}" : "{\"abierta\":true,\"turno\":" + t.id() + "}";
        } catch (Exception e) {
            falla(e, "No se pudo leer el estado de la caja");
            return "{\"abierta\":false,\"turno\":0}";
        }
    }

    /**
     * Egresos y resumen con los mismos filtros, en un solo JSON:
     * {"filas":[...],"r":{"total":..,"caja":..,"fuera":..,"anulados":..,"cats":[{"c":"..","m":..}]},"tope":false}.
     * Filas: id, f (fecha), d, c (categoría), m (monto), caja, t (turno, 0 si no hay), ts (estado del turno), u, an, mo, ap, pa (se puede anular),
     * inv (viene de una compra de insumos: al anular se puede revertir el inventario).
     */
    public String egresos(String desde, String hasta, String categoria, String origen, String estado, String texto) {
        if (!permitido()) return "{\"filas\":[],\"r\":{\"total\":0,\"caja\":0,\"fuera\":0,\"anulados\":0,\"cats\":[]},\"tope\":false}";
        try {
            FiltroEgresos f = leerFiltro(desde, hasta, categoria, origen, estado, texto);
            if (f == null) return "{\"filas\":[],\"r\":{\"total\":0,\"caja\":0,\"fuera\":0,\"anulados\":0,\"cats\":[]},\"tope\":false}";
            List<EgresoFila> filas = egresoDAO.listar(f);
            ResumenEgresos r = egresoDAO.resumen(f);
            StringBuilder b = new StringBuilder("{\"filas\":[");
            boolean primero = true;
            for (EgresoFila e : filas) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(e.id())
                        .append(",\"f\":").append(Json.texto(e.fecha()))
                        .append(",\"d\":").append(Json.texto(e.descripcion()))
                        .append(",\"c\":").append(Json.texto(e.categoria()))
                        .append(",\"m\":").append(e.montoCent())
                        .append(",\"caja\":").append(e.pagadoDesdeCaja())
                        .append(",\"t\":").append(e.idTurno() == null ? 0 : e.idTurno())
                        .append(",\"ts\":").append(Json.texto(e.estadoTurno()))
                        .append(",\"u\":").append(Json.texto(e.usuario()))
                        .append(",\"an\":").append(e.anulado())
                        .append(",\"mo\":").append(Json.texto(e.motivoAnulacion()))
                        .append(",\"ap\":").append(Json.texto(e.anuladoPor()))
                        .append(",\"pa\":").append(e.puedeAnular())
                        .append(",\"inv\":").append(e.compraInventario()).append('}');
            }
            b.append("],\"r\":{\"total\":").append(r.totalCent())
                    .append(",\"caja\":").append(r.cajaCent())
                    .append(",\"fuera\":").append(r.fueraCent())
                    .append(",\"anulados\":").append(r.anulados())
                    .append(",\"cats\":[");
            primero = true;
            for (MontoCategoria m : r.porCategoria()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"c\":").append(Json.texto(m.categoria())).append(",\"m\":").append(m.montoCent()).append('}');
            }
            return b.append("]},\"tope\":").append(filas.size() >= EgresoDAO.LIMITE).append('}').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer los egresos");
            return "{\"filas\":[],\"r\":{\"total\":0,\"caja\":0,\"fuera\":0,\"anulados\":0,\"cats\":[]},\"tope\":false}";
        }
    }

    /** origen: "caja" (efectivo del turno abierto) o "fuera" (transferencia, tarjeta, dinero propio). Devuelve {"ok":true}. */
    public String registrarEgreso(String descripcion, String categoria, String centavos, String origen) {
        if (!permitido()) return FALLO;
        try {
            Usuario u = web.usuarioActual();
            if (descripcion == null || descripcion.isBlank()) {
                error("Escribe una descripción");
                return FALLO;
            }
            if (descripcion.trim().length() > 120) {
                error("La descripción es muy larga (máximo 120 letras)");
                return FALLO;
            }
            if (categoria == null || !EgresoDAO.CATEGORIAS.contains(categoria.trim())) {
                error("Elige una categoría de la lista");
                return FALLO;
            }
            int monto = Integer.parseInt(centavos.trim());
            if (monto <= 0 || monto > 100_000_000) {
                error("Escribe un monto mayor que cero");
                return FALLO;
            }
            boolean deCaja = "caja".equals(origen);
            if (!deCaja && !"fuera".equals(origen)) {
                error("Elige de dónde sale el dinero");
                return FALLO;
            }
            Turno t = deCaja ? turnoDAO.abierto() : null;
            if (deCaja && t == null) {
                error("No hay una caja abierta: para pagar con efectivo de la caja primero ábrela");
                return FALLO;
            }
            egresoDAO.registrar(t == null ? null : t.id(), categoria.trim(), descripcion.trim(), monto, deCaja, u.id());
            return "{\"ok\":true}";
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
            return FALLO;
        } catch (Exception e) {
            falla(e, "No se pudo registrar el egreso");
            return FALLO;
        }
    }

    /**
     * Anula (no borra) un egreso. Pide un motivo. revertirInventario "1" = si el egreso viene de una compra de insumos,
     * también se resta esa compra del stock. Devuelve {"ok":true,"inv":..,"costo":..} (costo = se restauró el costo anterior).
     */
    public String anularEgreso(String id, String motivo, String revertirInventario) {
        if (!permitido()) return FALLO;
        try {
            if (motivo == null || motivo.isBlank()) {
                error("Escribe el motivo de la anulación");
                return FALLO;
            }
            boolean revertir = "1".equals(revertirInventario);
            boolean costo = egresoDAO.anular(Integer.parseInt(id.trim()), motivo.trim(), web.usuarioActual().id(), revertir);
            return "{\"ok\":true,\"inv\":" + revertir + ",\"costo\":" + costo + "}";
        } catch (NumberFormatException e) {
            error("No se pudo identificar el egreso");
            return FALLO;
        } catch (Exception e) {
            falla(e, "No se pudo anular el egreso");
            return FALLO;
        }
    }

    /** Guarda en un archivo CSV (se abre en Excel) lo que cumple los filtros: el administrador elige dónde. */
    public void exportarCsv(String desde, String hasta, String categoria, String origen, String estado, String texto) {
        if (!permitido()) return;
        try {
            FiltroEgresos f = leerFiltro(desde, hasta, categoria, origen, estado, texto);
            if (f == null) return;
            List<EgresoFila> filas = egresoDAO.listar(f);
            if (filas.isEmpty()) {
                error("No hay egresos para exportar con esos filtros");
                return;
            }
            // BOM al inicio para que Excel respete las tildes; el monto va como número (sin "L") para poder sumarlo
            StringBuilder csv = new StringBuilder("\uFEFFFecha,Descripción,Categoría,Origen,Turno,Registró,Monto (L),Estado,Motivo de anulación,Anuló\r\n");
            for (EgresoFila e : filas) {
                csv.append(campo(e.fecha())).append(',')
                        .append(campo(e.descripcion())).append(',')
                        .append(campo(e.categoria())).append(',')
                        .append(campo(e.pagadoDesdeCaja() ? "Caja" : "Fuera de caja")).append(',')
                        .append(e.idTurno() == null ? "" : e.idTurno()).append(',')
                        .append(campo(e.usuario())).append(',')
                        .append(String.format(Locale.ROOT, "%d.%02d", e.montoCent() / 100, e.montoCent() % 100)).append(',')
                        .append(e.anulado() ? "Anulado" : "Vigente").append(',')
                        .append(campo(e.motivoAnulacion())).append(',')
                        .append(campo(e.anuladoPor())).append("\r\n");
            }
            String contenido = csv.toString();
            String nombre = "egresos_" + f.desde() + "_a_" + f.hasta() + ".csv";
            Platform.runLater(() -> {
                try {
                    FileChooser fc = new FileChooser();
                    fc.setTitle("Guardar egresos");
                    fc.setInitialFileName(nombre);
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo CSV (Excel)", "*.csv"));
                    File destino = fc.showSaveDialog(null);
                    if (destino == null) return; // el administrador canceló
                    Files.writeString(destino.toPath(), contenido, StandardCharsets.UTF_8);
                    avisar("Archivo guardado: " + destino.getName());
                } catch (Exception e) {
                    falla(e, "No se pudo guardar el archivo");
                }
            });
        } catch (Exception e) {
            falla(e, "No se pudo exportar");
        }
    }

    // ---------- Apoyo ----------

    /** Valida los filtros que llegan como texto. Devuelve null (y avisa) si algo no es válido. */
    private FiltroEgresos leerFiltro(String desde, String hasta, String categoria, String origen, String estado, String texto) {
        try {
            if (desde == null || hasta == null || !FECHA.matcher(desde.trim()).matches() || !FECHA.matcher(hasta.trim()).matches()) {
                error("Las fechas deben tener el formato AAAA-MM-DD");
                return null;
            }
            if (desde.trim().compareTo(hasta.trim()) > 0) {
                error("La fecha inicial no puede ser mayor que la final");
                return null;
            }
            int o = Integer.parseInt(origen.trim());
            int s = Integer.parseInt(estado.trim());
            if (o < 0 || o > 2 || s < 0 || s > 2) throw new NumberFormatException();
            String t = texto == null ? "" : texto.trim();
            if (t.length() > 60) t = t.substring(0, 60);
            return new FiltroEgresos(desde.trim(), hasta.trim(), categoria == null ? "" : categoria.trim(), o, s, t);
        } catch (NumberFormatException e) {
            error("Revisa los filtros");
            return null;
        }
    }

    private static String lista(List<String> textos) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < textos.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Json.texto(textos.get(i)));
        }
        return b.append(']').toString();
    }

    /** Un campo de CSV entre comillas (las comillas internas se duplican). Evita que Excel ejecute fórmulas escritas en un texto. */
    private static String campo(String valor) {
        String v = valor == null ? "" : valor;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    /** Solo el administrador usa esta pantalla. */
    private boolean permitido() {
        if (esAdministrador()) return true;
        error("Solo el administrador puede ver los egresos");
        return false;
    }

    /** Sin "causa" = mensaje pensado para el administrador; con causa = error técnico, se muestra uno genérico. */
    private void falla(Exception e, String generico) {
        e.printStackTrace();
        error(e instanceof IllegalStateException && e.getCause() == null ? e.getMessage() : generico);
    }

    private void avisar(String mensaje) {
        engine.executeScript("window.aviso && window.aviso(" + Json.texto(mensaje) + ")");
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
