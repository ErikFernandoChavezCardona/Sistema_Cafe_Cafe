package application.controller;

import application.model.dao.EgresoDAO;
import application.model.dao.IngresoCajaDAO;
import application.model.dao.TurnoDAO;
import application.model.entity.Movimiento;
import application.model.entity.ResultadoCierre;
import application.model.entity.ResumenTurno;
import application.model.entity.Turno;
import application.util.Json;
import javafx.application.Platform;
import javafx.print.PrinterJob;
import javafx.scene.web.WebEngine;

/**
 * Puente de la pantalla Caja (caja_panel.html). Se registra como window.javaCaja.
 * Dinero: llega y sale en centavos enteros; las cantidades llegan como texto.
 */
public class CajaBridge {

    private final WebEngine engine;
    private final TurnoDAO turnoDAO = new TurnoDAO();
    private final EgresoDAO egresoDAO = new EgresoDAO();
    private final IngresoCajaDAO ingresoDAO = new IngresoCajaDAO();

    public CajaBridge(WebEngine engine) {
        this.engine = engine;
    }

    /** JSON con las cifras del turno abierto. Si no hay turno abierto devuelve "{}". */
    public String resumenTurno() {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) return "{}";
            return "{" + cifras(turnoDAO.resumen(t.id())) + "}";
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo leer el resumen de la caja");
            return "{}";
        }
    }

    /** JSON con la lista de movimientos del turno abierto (lo más reciente primero). */
    public String movimientosTurno() {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) return "[]";
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Movimiento m : turnoDAO.movimientos(t.id())) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"tipo\":").append(Json.texto(m.tipo()))
                        .append(",\"d\":").append(Json.texto(m.descripcion()))
                        .append(",\"x\":").append(Json.texto(m.detalle()))
                        .append(",\"m\":").append(m.montoCent())
                        .append(",\"h\":").append(Json.texto(m.hora()))
                        .append(",\"e\":").append(m.afectaEfectivo()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudieron leer los movimientos");
            return "[]";
        }
    }

    public void registrarEgreso(String descripcion, String categoria, String centavos) {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) {
                error("No hay una caja abierta");
                return;
            }
            if (descripcion == null || descripcion.isBlank()) {
                error("Escribe una descripción");
                return;
            }
            int monto = Integer.parseInt(centavos.trim());
            if (monto <= 0) {
                error("El monto debe ser mayor que cero");
                return;
            }
            String cat = categoria == null || categoria.isBlank() ? "General" : categoria.trim();
            egresoDAO.registrar(t.id(), cat, descripcion.trim(), monto);
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo registrar el egreso");
        }
    }

    public void registrarIngreso(String concepto, String centavos) {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) {
                error("No hay una caja abierta");
                return;
            }
            if (concepto == null || concepto.isBlank()) {
                error("Escribe un concepto");
                return;
            }
            int monto = Integer.parseInt(centavos.trim());
            if (monto <= 0) {
                error("El monto debe ser mayor que cero");
                return;
            }
            ingresoDAO.registrar(t.id(), concepto.trim(), monto);
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo registrar el ingreso");
        }
    }

    /** Cierra el turno y devuelve el resultado en JSON: {"ok":true,...} o {"ok":false}. */
    public String cerrarCaja(String contadoCentavos) {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) {
                error("No hay una caja abierta");
                return "{\"ok\":false}";
            }
            int contado = Integer.parseInt(contadoCentavos.trim());
            if (contado < 0) {
                error("El efectivo contado no puede ser negativo");
                return "{\"ok\":false}";
            }
            ResultadoCierre r = turnoDAO.cerrar(t.id(), contado);
            return "{\"ok\":true," + cifras(r.resumen())
                    + ",\"cierre\":" + Json.texto(r.cierreEn())
                    + ",\"contado\":" + r.contadoCent()
                    + ",\"diferencia\":" + r.diferenciaCent() + "}";
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
            return "{\"ok\":false}";
        } catch (Exception e) {
            e.printStackTrace();
            error("No se pudo cerrar la caja");
            return "{\"ok\":false}";
        }
    }

    /**
     * El WebView de JavaFX no soporta window.print(): se imprime desde Java.
     * Imprime la página tal como está; el CSS @media print de caja_panel.html deja solo el reporte.
     */
    public void imprimirReporte() {
        Platform.runLater(() -> {
            try {
                PrinterJob job = PrinterJob.createPrinterJob();
                if (job == null) {
                    error("No se encontró ninguna impresora");
                    return;
                }
                if (job.showPrintDialog(null)) {
                    engine.print(job);
                    job.endJob();
                }
            } catch (Exception e) {
                e.printStackTrace();
                error("No se pudo imprimir el reporte");
            }
        });
    }

    /** Las cifras comunes del resumen, sin llaves, para reutilizarlas en el resumen y en el cierre. */
    private static String cifras(ResumenTurno r) {
        return "\"id\":" + r.idTurno()
                + ",\"apertura\":" + Json.texto(r.aperturaEn())
                + ",\"inicial\":" + r.montoInicialCent()
                + ",\"efectivo\":" + r.ventasEfectivoCent()
                + ",\"tarjeta\":" + r.ventasTarjetaCent()
                + ",\"transferencia\":" + r.ventasTransferenciaCent()
                + ",\"ingresos\":" + r.ingresosCent()
                + ",\"egresos\":" + r.egresosCent()
                + ",\"esperado\":" + r.esperadoCent();
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
