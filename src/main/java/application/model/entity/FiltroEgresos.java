package application.model.entity;

/**
 * Filtros de la pantalla Egresos. desde y hasta son fechas AAAA-MM-DD (incluyen los dos días).
 * categoria "" = todas. origen: 0 todos, 1 desde la caja, 2 fuera de la caja. estado: 0 todos, 1 vigentes, 2 anulados.
 * texto "" = sin búsqueda.
 */
public record FiltroEgresos(String desde, String hasta, String categoria, int origen, int estado, String texto) {
}
