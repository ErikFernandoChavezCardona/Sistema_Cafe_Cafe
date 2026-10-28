package application.model.entity;

import java.util.List;

/**
 * Una factura de venta emitida. En los listados "lineas" va vacía; al abrir una factura trae los productos.
 * Dinero en centavos. impuestoPctX100: 15 % se guarda como 1500.
 */
public record Factura(int id, int idVenta, String numero, String cai, String rangoDesde, String rangoHasta,
                      String fechaLimite, String rtn, String cliente, int subtotalCent, int impuestoCent,
                      int impuestoPctX100, int totalCent, String atendio, String emitidaEn, boolean anulada,
                      String motivo, String metodoPago, int recibidoCent, int cambioCent, String comanda,
                      List<LineaFactura> lineas) {
}
