package application.model.entity;

/** Una línea de la bitácora: quién hizo qué y cuándo. */
public record FilaAuditoria(String fecha, String usuario, String accion, String detalle) {
}
