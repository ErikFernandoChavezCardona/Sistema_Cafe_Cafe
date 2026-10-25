package application.model.entity;

/** El precio va en centavos (L 55.00 = 5500). */
public record Producto(int id, int idCategoria, String nombre, int precioCent, String imagen) {
}
