package application.model.entity;

import java.util.List;

/** Una venta pendiente. En la lista de pendientes "lineas" va vacía; al abrirla trae los productos. */
public record Comanda(int id, String nombre, int totalCent, String creadaEn, List<LineaVenta> lineas) {
}
