package application.model.entity;

/**
 * Un producto tal como lo ve la pantalla Productos (incluye los inactivos). Dinero en centavos.
 * El costo sale de la receta: solo es confiable si el producto TIENE receta y todos sus insumos tienen costo.
 */
public record ProductoAdmin(int id, int idCategoria, String categoria, String nombre, int precioCent, boolean activo,
                            int lineasReceta, int lineasSinCosto, int costoCent) {

    public boolean costoCalculable() {
        return lineasReceta > 0 && lineasSinCosto == 0;
    }

    /** "Sin receta", "Falta costo" (algún insumo con costo 0) o "Completa". */
    public String estadoReceta() {
        if (lineasReceta == 0) return "Sin receta";
        return lineasSinCosto > 0 ? "Falta costo" : "Completa";
    }
}
