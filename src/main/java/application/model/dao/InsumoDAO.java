package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Insumo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class InsumoDAO {

    /** Insumos activos para elegir en las recetas. Su costo se llena desde la pantalla Insumos. */
    public List<Insumo> activos() {
        String sql = "SELECT id_insumo, nombre, unidad_base, costo_cent_x1000 FROM insumos WHERE activo = 1 ORDER BY nombre";
        List<Insumo> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Insumo(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer los insumos", e);
        }
    }
}
