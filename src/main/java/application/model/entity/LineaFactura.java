package application.model.entity;

/** Una línea impresa en la factura. Todo en centavos. */
public record LineaFactura(String producto, int cantidad, int precioCent, int subtotalCent) {
}
