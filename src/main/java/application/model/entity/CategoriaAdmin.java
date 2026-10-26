package application.model.entity;

/** Categoría para la pantalla de administración (incluye las ocultas y cuántos productos tiene). */
public record CategoriaAdmin(int id, String nombre, String icono, boolean activa, int productos) {
}
