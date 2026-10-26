package application.model.entity;

/**
 * Una línea de la receta: cuánto de un insumo gasta el producto por unidad vendida.
 * cantidad va en la unidad base del insumo (g, ml o ud). costoX1000 = centavos que cuestan 1000 unidades base.
 */
public record LineaReceta(int idInsumo, String insumo, String unidad, int cantidad, int costoX1000) {

    /** Costo de la línea en centavos (redondeado, igual que el cálculo en SQL). */
    public int costoCent() {
        return (int) Math.round(cantidad * (double) costoX1000 / 1000.0);
    }
}
