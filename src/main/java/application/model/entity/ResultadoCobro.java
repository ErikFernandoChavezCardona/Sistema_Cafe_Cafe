package application.model.entity;

/** Resultado de cobrar una venta. Todo en centavos. */
public record ResultadoCobro(int idVenta, int totalCent, int recibidoCent, int cambioCent) {
}
