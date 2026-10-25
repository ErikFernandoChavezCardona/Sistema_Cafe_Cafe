package application.model.entity;

/**
 * Una fila de la lista de movimientos del turno.
 * tipo: "Venta", "Ingreso" o "Egreso". detalle: método de pago (ventas) o categoría (egresos).
 * afectaEfectivo es false para ventas con tarjeta/transferencia: se ven en la lista pero no mueven el efectivo.
 */
public record Movimiento(String tipo, String descripcion, String detalle, int montoCent, String hora,
                         boolean afectaEfectivo) {
}
