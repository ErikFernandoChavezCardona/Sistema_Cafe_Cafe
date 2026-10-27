package application.model.entity;

/** insumos = cuántos insumos activos le compra el local a este proveedor. */
public record Proveedor(int id, String nombre, String telefono, String rtn, boolean activo, int insumos) {
}
