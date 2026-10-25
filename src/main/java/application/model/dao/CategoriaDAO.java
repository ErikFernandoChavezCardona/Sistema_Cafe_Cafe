package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.Categoria;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class CategoriaDAO {

    public List<Categoria> activas() {
        String sql = "SELECT id_categoria, nombre, icono FROM categorias WHERE activo = 1 ORDER BY orden, id_categoria";
        List<Categoria> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new Categoria(rs.getInt("id_categoria"), rs.getString("nombre"), rs.getString("icono")));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las categorías", e);
        }
    }
}
