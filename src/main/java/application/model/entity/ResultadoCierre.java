package application.model.entity;

/** Lo que se muestra en el reporte de cierre. diferenciaCent: positivo = sobrante, negativo = faltante. */
public record ResultadoCierre(ResumenTurno resumen, int contadoCent, int diferenciaCent, String cierreEn) {
}
