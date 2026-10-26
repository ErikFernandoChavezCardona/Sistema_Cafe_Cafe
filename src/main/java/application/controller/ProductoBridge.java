package application.controller;

import application.model.dao.CategoriaDAO;
import application.model.dao.InsumoDAO;
import application.model.dao.ProductoDAO;
import application.model.entity.CategoriaAdmin;
import application.model.entity.Insumo;
import application.model.entity.LineaReceta;
import application.model.entity.ProductoAdmin;
import application.model.entity.Usuario;
import application.util.Json;
import javafx.scene.web.WebEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puente de la pantalla Productos (productos.html). Se registra como window.javaProductos.
 * Solo el administrador puede ver y cambiar el menú. Dinero en centavos y cantidades como texto.
 * La receta llega como texto "idInsumo:cantidad,idInsumo:cantidad" (ejemplo: "3:150,7:1").
 */
public class ProductoBridge {

    private static final String FALLO = "{\"ok\":false}";

    private final WebEngine engine;
    private final WebBridge web; // para saber quién inició sesión
    private final ProductoDAO productoDAO = new ProductoDAO();
    private final CategoriaDAO categoriaDAO = new CategoriaDAO();
    private final InsumoDAO insumoDAO = new InsumoDAO();

    public ProductoBridge(WebEngine engine, WebBridge web) {
        this.engine = engine;
        this.web = web;
    }

    /** La pantalla lo pregunta al cargar: si no es administrador, vuelve a Venta. */
    public boolean esAdministrador() {
        Usuario u = web.usuarioActual();
        return u != null && u.esAdministrador();
    }

    /** JSON de todos los productos: id, c (categoría), cn (nombre categoría), n, p (precio), a (activo), l/s (líneas y sin costo), k (costo), e (estado). */
    public String productos() {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (ProductoAdmin p : productoDAO.todos()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(p.id())
                        .append(",\"c\":").append(p.idCategoria())
                        .append(",\"cn\":").append(Json.texto(p.categoria()))
                        .append(",\"n\":").append(Json.texto(p.nombre()))
                        .append(",\"p\":").append(p.precioCent())
                        .append(",\"a\":").append(p.activo())
                        .append(",\"l\":").append(p.lineasReceta())
                        .append(",\"s\":").append(p.lineasSinCosto())
                        .append(",\"k\":").append(p.costoCent())
                        .append(",\"e\":").append(Json.texto(p.estadoReceta())).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer los productos");
            return "[]";
        }
    }

    /** JSON de las categorías (también las ocultas): id, n, a (activa), np (cantidad de productos). */
    public String categorias() {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (CategoriaAdmin c : categoriaDAO.todas()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(c.id())
                        .append(",\"n\":").append(Json.texto(c.nombre()))
                        .append(",\"a\":").append(c.activa())
                        .append(",\"np\":").append(c.productos()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer las categorías");
            return "[]";
        }
    }

    /** JSON de los insumos para armar recetas: id, n, u (unidad), c (costo por 1000 unidades, en centavos). */
    public String insumos() {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (Insumo i : insumoDAO.activos()) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"id\":").append(i.id())
                        .append(",\"n\":").append(Json.texto(i.nombre()))
                        .append(",\"u\":").append(Json.texto(i.unidad()))
                        .append(",\"c\":").append(i.costoX1000()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudieron leer los insumos");
            return "[]";
        }
    }

    /** JSON de la receta de un producto: i (insumo), n, u, q (cantidad), c (costo por 1000 unidades). */
    public String receta(String idProducto) {
        if (!permitido()) return "[]";
        try {
            StringBuilder b = new StringBuilder("[");
            boolean primero = true;
            for (LineaReceta l : productoDAO.receta(Integer.parseInt(idProducto.trim()))) {
                if (!primero) b.append(',');
                primero = false;
                b.append("{\"i\":").append(l.idInsumo())
                        .append(",\"n\":").append(Json.texto(l.insumo()))
                        .append(",\"u\":").append(Json.texto(l.unidad()))
                        .append(",\"q\":").append(l.cantidad())
                        .append(",\"c\":").append(l.costoX1000()).append('}');
            }
            return b.append(']').toString();
        } catch (Exception e) {
            falla(e, "No se pudo leer la receta");
            return "[]";
        }
    }

    /**
     * Crea (id vacío) o actualiza un producto con su receta. Devuelve {"ok":true,"id":n} o {"ok":false}
     * (el motivo ya se mostró con mostrarError).
     */
    public String guardarProducto(String id, String idCategoria, String nombre, String centavos,
                                  String activo, String receta) {
        if (!permitido()) return FALLO;
        try {
            String limpio = nombre == null ? "" : nombre.trim();
            if (limpio.isEmpty()) {
                error("Escribe el nombre del producto");
                return FALLO;
            }
            if (limpio.length() > 60) {
                error("El nombre es muy largo (máximo 60 letras)");
                return FALLO;
            }
            int precio = Integer.parseInt(centavos.trim());
            if (precio <= 0 || precio > 100_000_000) {
                error("Escribe un precio mayor que cero");
                return FALLO;
            }
            List<int[]> lineas = leerReceta(receta);
            if (lineas == null) return FALLO;
            Integer idProducto = (id == null || id.isBlank()) ? null : Integer.valueOf(id.trim());
            int nuevo = productoDAO.guardar(idProducto, Integer.parseInt(idCategoria.trim()), limpio, precio,
                    "1".equals(activo), lineas);
            return "{\"ok\":true,\"id\":" + nuevo + "}";
        } catch (NumberFormatException e) {
            error("Revisa los datos del producto");
            return FALLO;
        } catch (Exception e) {
            falla(e, "No se pudo guardar el producto");
            return FALLO;
        }
    }

    /** Muestra ("1") u oculta ("0") un producto en Venta. */
    public void activarProducto(String id, String activo) {
        if (!permitido()) return;
        try {
            productoDAO.cambiarActivo(Integer.parseInt(id.trim()), "1".equals(activo));
        } catch (Exception e) {
            falla(e, "No se pudo cambiar el producto");
        }
    }

    /** Crea (id vacío) o renombra una categoría. Devuelve {"ok":true} o {"ok":false}. */
    public String guardarCategoria(String id, String nombre) {
        if (!permitido()) return FALLO;
        try {
            String limpio = nombre == null ? "" : nombre.trim();
            if (limpio.isEmpty() || limpio.length() > 40) {
                error("El nombre de la categoría debe tener entre 1 y 40 letras");
                return FALLO;
            }
            if (id == null || id.isBlank()) {
                categoriaDAO.crear(limpio);
            } else {
                categoriaDAO.renombrar(Integer.parseInt(id.trim()), limpio);
            }
            return "{\"ok\":true}";
        } catch (Exception e) {
            falla(e, "No se pudo guardar la categoría");
            return FALLO;
        }
    }

    public void activarCategoria(String id, String activa) {
        if (!permitido()) return;
        try {
            categoriaDAO.cambiarActivo(Integer.parseInt(id.trim()), "1".equals(activa));
        } catch (Exception e) {
            falla(e, "No se pudo cambiar la categoría");
        }
    }

    /** direccion: "-1" sube la categoría en el menú, "1" la baja. */
    public void moverCategoria(String id, String direccion) {
        if (!permitido()) return;
        try {
            int d = Integer.parseInt(direccion.trim());
            categoriaDAO.mover(Integer.parseInt(id.trim()), d < 0 ? -1 : 1);
        } catch (Exception e) {
            falla(e, "No se pudo mover la categoría");
        }
    }

    /** Convierte "3:150,7:1" en [[3,150],[7,1]]. Devuelve null (y avisa) si algo está mal. */
    private List<int[]> leerReceta(String texto) {
        List<int[]> lineas = new ArrayList<>();
        if (texto == null || texto.isBlank()) return lineas; // un producto puede guardarse sin receta
        Set<Integer> vistos = new HashSet<>();
        try {
            for (String parte : texto.split(",")) {
                String[] par = parte.trim().split(":");
                if (par.length != 2) throw new NumberFormatException();
                int insumo = Integer.parseInt(par[0].trim());
                int cantidad = Integer.parseInt(par[1].trim());
                if (cantidad <= 0) {
                    error("Las cantidades de la receta deben ser mayores que cero");
                    return null;
                }
                if (!vistos.add(insumo)) {
                    error("Un insumo está repetido en la receta");
                    return null;
                }
                lineas.add(new int[]{insumo, cantidad});
            }
        } catch (NumberFormatException e) {
            error("Revisa las cantidades de la receta (solo números enteros)");
            return null;
        }
        return lineas;
    }

    /** Solo el administrador cambia el menú. */
    private boolean permitido() {
        if (esAdministrador()) return true;
        error("Solo el administrador puede cambiar los productos");
        return false;
    }

    /** Sin "causa" = mensaje pensado para el administrador; con causa = error técnico, se muestra uno genérico. */
    private void falla(Exception e, String generico) {
        e.printStackTrace();
        error(e instanceof IllegalStateException && e.getCause() == null ? e.getMessage() : generico);
    }

    private void error(String mensaje) {
        engine.executeScript("window.mostrarError && window.mostrarError(" + Json.texto(mensaje) + ")");
    }
}
