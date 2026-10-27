package application.model.entity;

import java.util.List;

/** Totales de lo que se está viendo en la pantalla. Los anulados NO suman: solo se cuentan en "anulados". */
public record ResumenEgresos(int totalCent, int cajaCent, int fueraCent, int anulados, List<MontoCategoria> porCategoria) {
}
