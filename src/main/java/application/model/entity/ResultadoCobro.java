package application.model.entity;

/** Resultado de cobrar una venta. Todo en centavos. La factura se emite en el mismo instante del cobro. */
public record ResultadoCobro(int idVenta, int totalCent, int recibidoCent, int cambioCent,
                             int idFactura, String numeroFactura) {
}
