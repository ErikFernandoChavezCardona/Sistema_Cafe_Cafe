package application.model.entity;
public record InsumoAdmin(int id,String nombre,String unidad,int stock,int minimo,int costoX1000,Integer idProveedor,String proveedor,boolean activo,int productosUsados) { public int costoUnidad(){return unidad.equals("ud")?costoX1000/1000:costoX1000;} }
