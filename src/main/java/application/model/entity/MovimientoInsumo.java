package application.model.entity;

/**
 * Una línea del historial de un insumo. cantidad va con signo, en la unidad base: positiva suma stock, negativa resta.
 * usuario: quién lo hizo (nombre y usuario), o "" en movimientos viejos que no lo guardaron.
 */
public record MovimientoInsumo(String fecha, String tipo, int cantidad, String nota, String usuario) {
}
