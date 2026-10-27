package application.model.entity;

/**
 * Insumo para la pantalla Insumos. stock y stockMin van en la unidad base (g, ml o ud).
 * costoX1000 = centavos que cuestan 1000 unidades base (1 kg, 1 litro o 1000 unidades).
 * usadoEn = productos activos que lo llevan en su receta. movimientos = filas en su historial.
 */
public record InsumoAdmin(int id, String nombre, String unidad, int stock, int stockMin, int costoX1000,
                          int idProveedor, String proveedor, boolean activo, int usadoEn, int movimientos) {
}
