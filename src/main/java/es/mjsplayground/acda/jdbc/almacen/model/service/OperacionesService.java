package es.mjsplayground.acda.jdbc.almacen.model.service;

import es.mjsplayground.acda.jdbc.almacen.config.Database;
import es.mjsplayground.acda.jdbc.almacen.model.entity.*;
import es.mjsplayground.acda.jdbc.almacen.model.repository.*;
import java.math.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.slf4j.*;

public class OperacionesService {
  private static final Logger log = LoggerFactory.getLogger(OperacionesService.class);
  private final Database db;
  private final JdbcRecepcionRepository recepciones;
  private final JdbcProductoRepository productos = new JdbcProductoRepository();

  public OperacionesService(Database db) {
    this(db, new JdbcRecepcionRepository());
  }

  public OperacionesService(Database db, JdbcRecepcionRepository recepciones) {
    this.db = db;
    this.recepciones = recepciones;
  }

  public void preparar() {
    try (Connection c = db.abrir()) {
      recepciones.preparar(c);
    } catch (SQLException e) {
      throw fallo(e);
    }
  }

  // TODO Base: satisfacer las necesidades de búsqueda y revisión de precios del cliente.
  public List<Producto> buscar(String texto, int maximo) {
    if (maximo < 0) {
      throw new ReglaNegocioException("El máximo de existencias no puede ser negativo.");
    }

    String textoNormalizado = (texto == null) ? "" : texto.trim().toLowerCase(Locale.ROOT);
    String textoEscapado = textoNormalizado
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");

    return recepciones.buscar(db, textoEscapado, maximo);
  }

  public long revisarPrecio(long id, BigDecimal porcentaje) {
    if (id <= 0){
      throw new ReglaNegocioException("El id no puede ser vacio");
    }
    if (porcentaje == null){
      throw new ReglaNegocioException("El porcentaje no puede ser nulo");
    }
    if (porcentaje.compareTo(new BigDecimal("-50")) < 0 ||
            porcentaje.compareTo(new BigDecimal("100")) > 0){
      throw new ReglaNegocioException("El porcenaje no puede ser menor que -50 y mayot que 100");
    }
    if (porcentaje.stripTrailingZeros().scale() > 2){
      throw new ReglaNegocioException("El porcentaje no puede ser mayor a 2 decimales");
    }

    try(Connection connection = db.abrir()){
      Producto producto = productos.buscar(connection, id);
      if (producto == null){
        throw new ReglaNegocioException("Producto no encontrado");
      }

      BigDecimal factor = BigDecimal.ONE.add(porcentaje.movePointLeft(2));
      long nuevosCentimos = BigDecimal.valueOf(producto.precioCentimos())
              .multiply(factor)
              .setScale(0, RoundingMode.HALF_UP)
              .longValue();

      if (nuevosCentimos <= 0 || nuevosCentimos > 100_000_000L) {
        throw new ReglaNegocioException("El nuevo precio debe estar entre 0,01 € y 1.000.000 €");
      }

      Producto productoAcualizado = new Producto(producto.id(), producto.nombre(), nuevosCentimos, producto.stock());
      productos.guardar(connection, productoAcualizado);

      return id;

    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
  }

  // TODO Aplicación y Reto avanzado: registrar entregas completas de forma segura.
  public long recibirUna(LineaRecepcion linea) {
    if (linea == null){
      throw new ReglaNegocioException("Linea vacia");
    }
    if (linea.productoId() <= 0) {
      throw new ReglaNegocioException("El identificador del producto no es válido.");
    }
    if (linea.cantidad() <= 0) {
      throw new ReglaNegocioException("Las unidades recibidas deben ser mayores que cero.");
    }
    if (linea.costeUnitarioCentimos() <= 0 || linea.costeUnitarioCentimos() > 100_000_000L) {
      throw new ReglaNegocioException("El coste de compra debe estar entre 0,01 € y 1.000.000 €");
    }

    long id = linea.productoId();
    try (Connection connection = db.abrir()) {
      connection.setAutoCommit(false);
      try{
        Producto producto = productos.buscar(connection, id);

        if (producto == null){
          throw new ReglaNegocioException("El producto seleccionado no existe");
        }
        int cantidad = linea.cantidad() + producto.stock();
        Producto productoActualizado = new Producto(id, producto.nombre(), producto.precioCentimos(), cantidad);
        productos.guardar(connection, productoActualizado);
        String fechaRecepcion = String.valueOf(LocalDateTime.now());

        Recepcion justificante = new Recepcion(
                producto.nombre(),
                linea.cantidad(),
                linea.costeUnitarioCentimos(),
                fechaRecepcion
        );

        long idJustificacion = recepciones.guardar(connection, producto.id(), justificante);
        connection.commit();
        return idJustificacion;
      } catch (SQLException e) {
          connection.rollback();
          throw fallo(e);
      }
    } catch (SQLException e) {
        throw fallo(e);
    }
  }

  public long recibir(List<LineaRecepcion> lineas, long presupuesto) {
    if (lineas == null || lineas.isEmpty()) {
      throw new ReglaNegocioException("Debe indicar al menos una línea de recepción.");
    }
    if (presupuesto <= 0) {
      throw new ReglaNegocioException("El presupuesto debe ser mayor que cero.");
    }
    Set<Long> productosVistos = new HashSet<>();
    long coste = 0;
    for (LineaRecepcion linea : lineas) {
      if (linea == null) {
        throw new ReglaNegocioException("Ninguna línea de recepción puede ser nula.");
      }
      long idProducto = linea.productoId();
      if (idProducto <= 0) {
        throw new ReglaNegocioException("El identificador del producto no es válido.");
      }
      if (!productosVistos.add(idProducto)) {
        throw new ReglaNegocioException("El producto con identificador " + idProducto + " aparece repetido en la entrega.");
      }
      if (linea.cantidad() <= 0) {
        throw new ReglaNegocioException("Las unidades recibidas deben ser mayores que cero.");
      }
      if (linea.costeUnitarioCentimos() <= 0) {
        throw new ReglaNegocioException("El coste de compra por unidad debe ser mayor que cero.");
      }
      coste = coste + linea.costeUnitarioCentimos();
    }
    if (coste > presupuesto){
      throw new ReglaNegocioException("El presupuesto no puede ser menor que el coste total.");
    }

    try(Connection connection = db.abrir()){
      connection.setAutoCommit(false);
      try{
        for (LineaRecepcion linea : lineas) {
          long productoId = linea.productoId();
          Producto producto = productos.buscar(connection, productoId);
          if (producto == null){
            throw new ReglaNegocioException("EL producto es erroneo");
          }
          int nuevoStock = producto.stock() + linea.cantidad();
          Producto productoActualizado = new Producto(
                  productoId,
                  producto.nombre(),
                  producto.precioCentimos(),
                  nuevoStock
          );
          productos.guardar(connection, productoActualizado);

          long totalCosteLinea = linea.cantidad() * linea.costeUnitarioCentimos();
          Recepcion justificacion = new Recepcion(
                  producto.nombre(),
                  linea.cantidad(),
                  totalCosteLinea,
                  LocalDateTime.now().toString()
          );
          recepciones.guardar(connection, productoId, justificacion);
        }
        connection.commit();
        return coste;
      } catch (SQLException | ReglaNegocioException e) {
          throw new RuntimeException(e);
      }
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
  }

  // TODO Aplicación: consultar justificantes persistidos; vacío mientras no se implemente.
  public List<Recepcion> historial() {
    try(Connection connection = db.abrir()){
        return recepciones.historial(connection);
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
  }

  private ReglaNegocioException pendiente() {
    return new ReglaNegocioException(
        "Función de la práctica final pendiente de implementar; no se ha guardado ningún cambio.");
  }

  private PersistenciaException fallo(SQLException e) {
    log.error("Error al preparar operaciones", e);
    return new PersistenciaException("No se pudo preparar la práctica final.", e);
  }
}
