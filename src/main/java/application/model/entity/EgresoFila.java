package application.model.entity;

/**
 * Un egreso tal como se muestra en la pantalla Egresos. Dinero en centavos.
 * compraInventario es true si el egreso salió de una compra de insumos y sigue vigente: al anularlo se puede
 * elegir si también se revierte esa compra en el inventario.
 * idTurno es null en los egresos pagados fuera de la caja; estadoTurno es "Abierta" o "Cerrada" (o null si no hay turno).
 */
public record EgresoFila(int id, String fecha, String descripcion, String categoria, int montoCent,
                         boolean pagadoDesdeCaja, Integer idTurno, String estadoTurno, String usuario,
                         boolean anulado, String motivoAnulacion, String anuladoPor,
                         boolean compraInventario) {

    /**
     * Un egreso se puede anular si sigue vigente y NO pertenece a un turno ya cerrado
     * (el reporte de cierre de ese turno ya se imprimió y no debe cambiar).
     */
    public boolean puedeAnular() {
        return !anulado && (idTurno == null || "Abierta".equals(estadoTurno));
    }
}
