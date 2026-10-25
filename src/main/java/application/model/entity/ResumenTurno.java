package application.model.entity;

/**
 * Cifras del turno, todo en centavos. El efectivo esperado se calcula en Java
 * con la misma fórmula de la guía: inicial + ventas en efectivo + ingresos - egresos pagados desde caja.
 * Tarjeta y transferencia se muestran en el reporte pero NO suman al efectivo.
 */
public record ResumenTurno(int idTurno, String aperturaEn, int montoInicialCent,
                           int ventasEfectivoCent, int ventasTarjetaCent, int ventasTransferenciaCent,
                           int ingresosCent, int egresosCent) {

    public int esperadoCent() {
        return montoInicialCent + ventasEfectivoCent + ingresosCent - egresosCent;
    }
}
