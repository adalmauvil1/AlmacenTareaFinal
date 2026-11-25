package es.mjsplayground.acda.jdbc.almacen.model.repository;

import es.mjsplayground.acda.jdbc.almacen.config.Database;
import es.mjsplayground.acda.jdbc.almacen.model.entity.*;
import es.mjsplayground.acda.jdbc.almacen.model.service.ReglaNegocioException;

import javax.xml.crypto.Data;
import java.sql.*;
import java.util.*;

public class JdbcRecepcionRepository {
  public void preparar(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.executeUpdate(
          "CREATE TABLE IF NOT EXISTS recepcion (id INTEGER PRIMARY KEY, producto_id INTEGER NOT"
              + " NULL REFERENCES producto(id), cantidad INTEGER NOT NULL CHECK(cantidad>0), total"
              + " INTEGER NOT NULL CHECK(total>0), fecha TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }
  }

  public List<Producto> buscar(Database db, String textoEscapado, int maximoStock) {
    String sql = """
        SELECT p.id, p.nombre, p.precio, p.stock
        FROM producto p
        WHERE LOWER(p.nombre) LIKE ? ESCAPE '\\'
          AND p.stock <= ?
        ORDER BY p.nombre ASC
        """;

    List<Producto> resultados = new ArrayList<>();

    try (Connection connection = db.abrir();
         PreparedStatement s = connection.prepareStatement(sql)) {

      s.setString(1, "%" + textoEscapado + "%");
      s.setInt(2, maximoStock);

      try (ResultSet set = s.executeQuery()) {
        while (set.next()) {
          Producto p = new Producto(
                  set.getLong("id"),
                  set.getString("nombre"),
                  set.getLong("precio"),
                  set.getInt("stock")
          );
          resultados.add(p);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Error al buscar productos", e);
    }
    return resultados;
  }


  public long guardar(Connection connection, long productoId, Recepcion recepcion){
    if (recepcion == null){
      throw new ReglaNegocioException("La recepcion es nula");
    }
    String sql = """
              INSERT INTO recepcion (producto_id, cantidad, total) VALUES (?,?,?)
              """;
    try(PreparedStatement sentencia = connection.prepareStatement(sql)) {
        sentencia.setLong(1, productoId);
        sentencia.setLong(2, recepcion.cantidad());
        sentencia.setLong(3, recepcion.totalCentimos());

        sentencia.executeUpdate();

      try (ResultSet set = sentencia.getGeneratedKeys()) {
        if (set.next()) {
          return set.getLong(1);
        }
          throw new ReglaNegocioException("NO se ha modificado nada");
        }
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
  }

  public List<Recepcion> historial(Connection connection) {
    String sql = """
            SELECT r.id, p.nombre, r.cantidad, r.total, r.fecha
            FROM recepcion r
            JOIN producto p ON p.id = r.producto_id
            ORDER BY r.id DESC
            """;
    List<Recepcion> recepcions = new ArrayList<>();
    try(PreparedStatement sentencia = connection.prepareStatement(sql)){
        try(ResultSet set = sentencia.executeQuery()){
          while (set.next()){
            recepcions.add(new Recepcion(
                    set.getString("nombre"),
                    set.getInt("cantidad"),
                    set.getInt("total"),
                    set.getString("fecha")
            ));
          }
        }
        return recepcions;
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
  }

  // TODO Práctica final HECHO: incorporar aquí las operaciones de persistencia que necesites.
}
