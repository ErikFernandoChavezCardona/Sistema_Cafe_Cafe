package application.model.entity;

/** Insumo disponible para armar recetas. costoX1000: centavos por cada 1000 unidades base (1 kg, 1 litro...). */
public record Insumo(int id, String nombre, String unidad, int costoX1000) {
}
