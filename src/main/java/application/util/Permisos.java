package application.util;

import application.model.entity.Usuario;

import java.util.Set;

/**
 * Qué puede hacer cada rol. Un solo lugar para decidir, así no queda repartido por los puentes.
 * <ul>
 * <li>Cajero: vender, abrir y cerrar caja, registrar egresos e ingresos de caja.</li>
 * <li>Administrador: todo lo anterior y además anular movimientos, productos, insumos, egresos,
 * facturas, reportes, usuarios, respaldos y auditoría.</li>
 * </ul>
 */
public final class Permisos {

    public enum Accion { VENDER, ABRIR_CERRAR_CAJA, MOVER_EFECTIVO, ANULAR, ADMINISTRAR }

    private static final Set<String> PAGINAS_CAJERO = Set.of("login.html", "pos.html", "caja.html", "caja_panel.html", "cuenta.html");

    private Permisos() {
    }

    public static boolean puede(Usuario u, Accion a) {
        if (u == null) return false;
        if (u.esAdministrador()) return true;
        return a == Accion.VENDER || a == Accion.ABRIR_CERRAR_CAJA || a == Accion.MOVER_EFECTIVO;
    }

    public static boolean puedeAbrir(Usuario u, String pagina) {
        if ("login.html".equals(pagina)) return true;
        if (u == null) return false;
        if (u.esAdministrador()) return true;
        return PAGINAS_CAJERO.contains(pagina);
    }
}
