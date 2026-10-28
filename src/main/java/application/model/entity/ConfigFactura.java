package application.model.entity;

/**
 * Autorización del SAR (CAI y rango) y datos del negocio que salen en la factura.
 * desde / hasta / siguiente son la parte numérica del correlativo (el prefijo va aparte: 000-001-01).
 * limite: fecha límite de emisión como AAAA-MM-DD. isvX100: 15 % se guarda como 1500.
 */
public record ConfigFactura(String cai, int desde, int hasta, String limite, int siguiente, String prefijo,
                            int isvX100, String negocio, String rtn, String direccion, String telefono,
                            String email, boolean imprimirAuto) {

    /** Número completo, por ejemplo 000-001-01-00056759. */
    public String numero(int n) {
        return prefijo + "-" + String.format("%08d", n);
    }

    /** Cuántas facturas quedan en el rango autorizado. */
    public int restantes() {
        return Math.max(0, hasta - siguiente + 1);
    }

    /** null si se puede facturar; si no, el motivo en español (listo para mostrar). hoy = AAAA-MM-DD. */
    public String problema(String hoy) {
        if (cai.isBlank() || desde <= 0 || hasta < desde || prefijo.isBlank())
            return "Falta configurar el CAI en Facturas › Configuración del CAI";
        if (limite.isBlank() || limite.compareTo(hoy) < 0)
            return "El CAI venció el " + fechaLatina(limite) + ": pide uno nuevo al SAR y configúralo en Facturas";
        if (siguiente > hasta)
            return "Se acabó el rango autorizado del CAI: pide uno nuevo al SAR y configúralo en Facturas";
        if (siguiente < desde)
            return "El siguiente número está fuera del rango: revísalo en Facturas › Configuración del CAI";
        return null;
    }

    /** AAAA-MM-DD → DD/MM/AAAA (si no tiene ese formato, devuelve el texto tal cual). */
    public static String fechaLatina(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) return iso == null ? "" : iso;
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }
}
