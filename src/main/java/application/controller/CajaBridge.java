package application.controller;

import application.model.connection.Respaldo;
import application.model.dao.AuditoriaDAO;
import application.model.dao.EgresoDAO;
import application.model.dao.IngresoCajaDAO;
import application.model.dao.TurnoDAO;
import application.model.entity.Movimiento;
import application.model.entity.ResultadoCierre;
import application.model.entity.ResumenTurno;
import application.model.entity.Turno;
import application.model.entity.Usuario;
import application.util.Json;
import application.util.Permisos;
import application.util.Registro;
import javafx.application.Platform;
import javafx.print.PrinterJob;
import javafx.scene.web.WebEngine;

/**
 * Puente de la pantalla Caja (caja_panel.html). Se registra como window.javaCaja.
 * Dinero: llega y sale en centavos enteros; las cantidades llegan como texto.
 */
public class CajaBridge {

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final TurnoDAO turnoDAO = new TurnoDAO();
    private final EgresoDAO egresoDAO = new EgresoDAO();
    private final IngresoCajaDAO ingresoDAO = new IngresoCajaDAO();

    public CajaBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** JSON con las cifras del turno abierto. Si no hay turno abierto devuelve "{}". */
    public String resumenTurno() {
        try {
            if (web.usuarioActual() == null) return "{}";
            Turno t = turnoDAO.abierto();
            if (t == null) return "{}";
            return "{" + cifras(turnoDAO.resumen(t.id())) + "}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo leer el resumen de la caja");
            return "{}";
        }
    }

    /** JSON con la lista de movimientos del turno abierto (lo más reciente primero). */
    public String movimientosTurno() {
        try {
            if (web.usuarioActual() == null) return "[]";
            Turno t = turnoDAO.abierto();
            if (t == null) return "[]";
            return movimientosJson(t.id());
        } catch (Exception e) {
            Registro.error(e);
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
            Usuario u = web.usuarioActual();
            if (u == null) {
                error("Inicia sesión de nuevo para registrar el egreso");
                return;
            }
            if (!Permisos.puede(u, Permisos.Accion.MOVER_EFECTIVO)) {
                error("No tienes permiso para registrar egresos");
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
            if (!EgresoDAO.CATEGORIAS.contains(cat)) {
                error("Elige una categoría de la lista");
                return;
            }
            egresoDAO.registrar(t.id(), cat, descripcion.trim(), monto, true, u.id());
            AuditoriaDAO.registrar(u, "Egreso de caja", cat + " · " + descripcion.trim() + " · " + lempiras(monto));
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (IllegalStateException e) {
            Registro.error(e);
            // Sin causa = mensaje pensado para el cajero (por ejemplo, la caja se cerró)
            error(e.getCause() == null ? e.getMessage() : "No se pudo registrar el egreso");
        } catch (Exception e) {
            Registro.error(e);
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
            Usuario u = web.usuarioActual();
            if (u == null) {
                error("Inicia sesión de nuevo para registrar el ingreso");
                return;
            }
            if (!Permisos.puede(u, Permisos.Accion.MOVER_EFECTIVO)) {
                error("No tienes permiso para registrar ingresos");
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
            AuditoriaDAO.registrar(u, "Ingreso de caja", concepto.trim() + " · " + lempiras(monto));
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo registrar el ingreso");
        }
    }

    /**
     * Anula un ingreso o egreso del turno abierto. No se borra: queda en el historial con el motivo
     * y quién lo anuló, y deja de sumar o restar al efectivo esperado.
     */
    public String anularMovimiento(String tipo, String id, String motivo, String revertirInventario) {
        try {
            Turno t = turnoDAO.abierto();
            if (t == null) {
                error("No hay una caja abierta");
                return "{\"ok\":false}";
            }
            Usuario u = web.usuarioActual();
            if (u == null) {
                error("Inicia sesión de nuevo para anular");
                return "{\"ok\":false}";
            }
            if (!Permisos.puede(u, Permisos.Accion.ANULAR)) {
                AuditoriaDAO.registrar(u, "Acceso denegado", "Intentó anular un movimiento de caja");
                error("Solo el administrador puede anular movimientos");
                return "{\"ok\":false}";
            }
            if (motivo == null || motivo.isBlank()) {
                error("Escribe el motivo de la anulación");
                return "{\"ok\":false}";
            }
            int idMovimiento = Integer.parseInt(id.trim());
            boolean revertir = "1".equals(revertirInventario);
            if ("Egreso".equals(tipo)) {
                boolean costo = egresoDAO.anular(idMovimiento, motivo.trim(), u.id(), revertir);
                AuditoriaDAO.registrar(u, "Egreso anulado", "N.º " + idMovimiento + " · " + motivo.trim()
                        + (revertir ? " · también se revirtió el inventario" : ""));
                return "{\"ok\":true,\"inv\":" + revertir + ",\"costo\":" + costo + "}";
            }
            if ("Ingreso".equals(tipo)) {
                if (revertir) {
                    error("Un ingreso no tiene inventario que revertir");
                    return "{\"ok\":false}";
                }
                ingresoDAO.anular(idMovimiento, motivo.trim(), u.id());
                AuditoriaDAO.registrar(u, "Ingreso anulado", "N.º " + idMovimiento + " · " + motivo.trim());
                return "{\"ok\":true,\"inv\":false,\"costo\":false}";
            }
            error("Solo se pueden anular ingresos y egresos");
            return "{\"ok\":false}";
        } catch (NumberFormatException e) {
            error("No se pudo identificar el movimiento");
            return "{\"ok\":false}";
        } catch (IllegalStateException e) {
            Registro.error(e);
            // Sin causa = mensaje pensado para el cajero (ya anulado, caja cerrada, stock insuficiente para revertir)
            error(e.getCause() == null ? e.getMessage() : "No se pudo anular el movimiento");
            return "{\"ok\":false}";
        } catch (Exception e) {
            Registro.error(e);
            error("No se pudo anular el movimiento");
            return "{\"ok\":false}";
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
            Usuario u = web.usuarioActual();
            if (u == null) {
                error("Inicia sesión de nuevo para cerrar la caja");
                return "{\"ok\":false}";
            }
            if (!Permisos.puede(u, Permisos.Accion.ABRIR_CERRAR_CAJA)) {
                error("No tienes permiso para cerrar la caja");
                return "{\"ok\":false}";
            }
            int contado = Integer.parseInt(contadoCentavos.trim());
            if (contado < 0) {
                error("El efectivo contado no puede ser negativo");
                return "{\"ok\":false}";
            }
            ResultadoCierre r = turnoDAO.cerrar(t.id(), contado, u.id());
            AuditoriaDAO.registrar(u, "Caja cerrada", "Contado " + lempiras(contado) + " · esperado " + lempiras(r.resumen().esperadoCent())
                    + " · diferencia " + lempiras(r.diferenciaCent()));
            // Cierre de caja = momento ideal para una copia de seguridad (si falla, el cierre ya quedó hecho)
            Respaldo.hacerCopia("Cierre de caja", u);
            return "{\"ok\":true," + cifras(r.resumen())
                    + ",\"cierre\":" + Json.texto(r.cierreEn())
                    + ",\"cerro\":" + Json.texto(r.usuarioCerro())
                    + ",\"movs\":" + movimientosJsonSeguro(t.id())
                    + ",\"contado\":" + r.contadoCent()
                    + ",\"diferencia\":" + r.diferenciaCent() + "}";
        } catch (NumberFormatException e) {
            error("Escribe un monto válido");
            return "{\"ok\":false}";
        } catch (Exception e) {
            Registro.error(e);
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
                Registro.error(e);
                error("No se pudo imprimir el reporte");
            }
        });
    }

    /** Lista de movimientos de un turno (abierto o cerrado) en JSON; la usa la pantalla y el reporte de cierre. */
    private String movimientosJson(int idTurno) {
        StringBuilder b = new StringBuilder("[");
        boolean primero = true;
        for (Movimiento m : turnoDAO.movimientos(idTurno)) {
            if (!primero) b.append(',');
            primero = false;
            b.append("{\"tipo\":").append(Json.texto(m.tipo()))
                    .append(",\"id\":").append(m.id())
                    .append(",\"d\":").append(Json.texto(m.descripcion()))
                    .append(",\"x\":").append(Json.texto(m.detalle()))
                    .append(",\"m\":").append(m.montoCent())
                    .append(",\"h\":").append(Json.texto(m.hora()))
                    .append(",\"e\":").append(m.afectaEfectivo())
                    .append(",\"an\":").append(m.anulado())
                    .append(",\"mo\":").append(Json.texto(m.motivoAnulacion()))
                    .append(",\"ap\":").append(Json.texto(m.anuladoPor()))
                    .append(",\"inv\":").append(m.compraInventario()).append('}');
        }
        return b.append(']').toString();
    }

    /**
     * Para el reporte de cierre: si la lista fallara, la caja YA quedó cerrada,
     * así que no se puede decir que el cierre falló. Se entrega el reporte sin el detalle.
     */
    private String movimientosJsonSeguro(int idTurno) {
        try {
            return movimientosJson(idTurno);
        } catch (Exception e) {
            Registro.error(e);
            return "[]";
        }
    }

    /** Las cifras comunes del resumen, sin llaves, para reutilizarlas en el resumen y en el cierre. */
    private static String cifras(ResumenTurno r) {
        return "\"id\":" + r.idTurno()
                + ",\"apertura\":" + Json.texto(r.aperturaEn())
                + ",\"abrio\":" + Json.texto(r.usuarioAbrio())
                + ",\"nventas\":" + r.cantidadVentas()
                + ",\"ventas\":" + r.totalVentasCent()
                + ",\"inicial\":" + r.montoInicialCent()
                + ",\"efectivo\":" + r.ventasEfectivoCent()
                + ",\"tarjeta\":" + r.ventasTarjetaCent()
                + ",\"transferencia\":" + r.ventasTransferenciaCent()
                + ",\"ingresos\":" + r.ingresosCent()
                + ",\"egresos\":" + r.egresosCent()
                + ",\"esperado\":" + r.esperadoCent();
    }

    private static String lempiras(int centavos) {
        return String.format("L %.2f", centavos / 100.0);
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
