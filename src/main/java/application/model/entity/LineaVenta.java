package application.model.entity;

/** Un producto y cuántas unidades se piden de él. El precio NO viaja aquí: se lee de la base. */
public record LineaVenta(int idProducto, int cantidad) {
}
