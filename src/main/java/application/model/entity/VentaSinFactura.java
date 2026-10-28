package application.model.entity;

import java.util.List;

/** Venta cobrada que no tiene factura vigente (ventas anteriores a la facturación automática o facturas anuladas). */
public record VentaSinFactura(int id, String comanda, String metodoPago, int totalCent, String cobradaEn,
                              List<LineaFactura> lineas) {
}
