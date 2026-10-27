package application.model.entity;

/**
 * Un egreso tal como se muestra en la pantalla Egresos. Dinero en centavos.
 * idTurno es null en los egresos pagados fuera de la caja; estadoTurno es "Abierta" o "Cerrada" (o null si no hay turno).
 */
public record EgresoFila(int id, String fecha, String descripcion, String categoria, int montoCent,
                         boolean pagadoDesdeCaja, Integer idTurno, String estadoTurno, String usuario,
                         boolean anulado, String motivoAnulacion, String anuladoPor) {

    /**
     * Un egreso se puede anular si sigue vigente y NO pertenece a un turno ya cerrado
     * (el reporte de cierre de ese turno ya se imprimió y no debe cambiar).
     */
    public boolean puedeAnular() {
        return !anulado && (idTurno == null || "Abierta".equals(estadoTurno));
    }
}
