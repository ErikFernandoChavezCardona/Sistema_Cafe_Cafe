package application.model.entity;

/**
 * Una autorización del SAR (CAI) con los datos de la compra del talonario y cuánto se ha usado.
 * usadas = facturas emitidas con este CAI (las anuladas también gastan su número).
 */
public record AutorizacionCai(int id, String cai, String prefijo, int desde, int hasta, String limite,
                              String fechaCompra, String imprenta, int costoCent, String nota, String registradoPor,
                              String registradoEn, int usadas, int anuladas, String primera, String ultima) {

    public int total() {
        return hasta - desde + 1;
    }

    public int sinUsar() {
        return Math.max(0, total() - usadas);
    }
}
