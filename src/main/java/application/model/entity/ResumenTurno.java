package application.model.entity;

/**
 * Cifras del turno, todo en centavos. El efectivo esperado se calcula en Java
 * con la misma fórmula de la guía: inicial + ventas en efectivo + ingresos - egresos pagados desde caja.
 * Tarjeta y transferencia se muestran en el reporte pero NO suman al efectivo.
 * usuarioAbrio: nombre y usuario de quien abrió la caja. cantidadVentas: solo ventas Completadas.
 */
public record ResumenTurno(int idTurno, String aperturaEn, String usuarioAbrio, int montoInicialCent,
                           int ventasEfectivoCent, int ventasTarjetaCent, int ventasTransferenciaCent,
                           int cantidadVentas, int ingresosCent, int egresosCent) {

    public int esperadoCent() {
        return montoInicialCent + ventasEfectivoCent + ingresosCent - egresosCent;
    }

    /** Todo lo vendido en el turno, sin importar cómo pagó el cliente. */
    public int totalVentasCent() {
        return ventasEfectivoCent + ventasTarjetaCent + ventasTransferenciaCent;
    }
}
