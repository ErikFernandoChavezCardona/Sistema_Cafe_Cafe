package application.model.entity;

/** Una línea del historial de un insumo. cantidad va con signo, en la unidad base: positiva suma stock, negativa resta. */
public record MovimientoInsumo(String fecha, String tipo, int cantidad, String nota) {
}
