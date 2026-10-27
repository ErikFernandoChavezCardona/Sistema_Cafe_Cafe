package application.model.entity;

/**
 * Una fila de la lista de movimientos del turno.
 * tipo: "Venta", "Ingreso" o "Egreso". detalle: método de pago (ventas) o categoría (egresos).
 * afectaEfectivo es false para ventas con tarjeta/transferencia y para movimientos anulados.
 * compraInventario es true en un egreso que salió de una compra de insumos y todavía no se anuló: al anularlo
 * se puede elegir si también se revierte esa compra en el inventario.
 * Un movimiento anulado no se borra: guarda el motivo y quién lo anuló para que quede en el historial.
 */
public record Movimiento(String tipo, int id, String descripcion, String detalle, int montoCent, String hora,
                         boolean afectaEfectivo, boolean anulado, String motivoAnulacion, String anuladoPor,
                         boolean compraInventario) {
}
