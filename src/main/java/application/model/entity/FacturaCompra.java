package application.model.entity;

/** Factura que un proveedor le entrega al negocio (respaldo de compras). */
public record FacturaCompra(int id, String proveedor, String numero, String fecha, String nota, int totalCent) {
}
