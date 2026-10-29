package application.model.dao;

import application.model.connection.DatabaseConnection;
import application.model.entity.AutorizacionCai;
import application.model.entity.ConfigFactura;
import application.model.entity.Factura;
import application.model.entity.FacturaCompra;
import application.model.entity.LineaFactura;
import application.model.entity.VentaSinFactura;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facturas de venta y de compra, y la configuración del CAI.
 * Reglas: la factura de una venta se emite DENTRO de la transacción del cobro (VentaDAO.cobrar llama a emitir),
 * el correlativo del CAI se consume en esa misma transacción y una factura nunca se borra: se anula.
 * Dinero en centavos; los precios del menú ya incluyen el ISV, la factura solo lo desglosa.
 */
public class FacturaDAO {

    private static final String CONSUMIDOR_FINAL = "Consumidor final";

    private static final String BASE = "SELECT f.id_factura_venta, f.id_venta, f.numero, COALESCE(f.cai, ''), "
            + "COALESCE(f.rango_desde, ''), COALESCE(f.rango_hasta, ''), COALESCE(f.fecha_limite, ''), "
            + "COALESCE(f.rtn_cliente, ''), f.nombre_cliente, f.subtotal_cent, f.impuesto_cent, f.impuesto_pct_x100, "
            + "f.total_cent, COALESCE(u.nombre, uv.nombre, ''), f.emitida_en, f.anulada, "
            + "COALESCE(f.motivo_anulacion, ''), COALESCE(v.metodo_pago, ''), v.recibido_cent, v.cambio_cent, "
            + "v.nombre_comanda "
            + "FROM facturas_venta f JOIN ventas v ON v.id_venta = f.id_venta "
            + "LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario "
            + "LEFT JOIN usuarios uv ON uv.id_usuario = v.id_usuario ";

    // ---------- Emisión (se usa desde el cobro) ----------

    /**
     * Emite la factura de una venta YA COBRADA dentro de la transacción recibida y consume el siguiente número del CAI.
     * Si el CAI no permite facturar (sin configurar, vencido o agotado) lanza IllegalStateException y el cobro se revierte.
     * nombre / rtn vacíos = Consumidor final. Devuelve el id de la factura.
     */
    public static int emitir(Connection c, int idVenta, int idUsuario, String nombre, String rtn) throws SQLException {
        ConfigFactura cfg = leerConfig(c);
        String problema = cfg.problema(LocalDate.now().toString());
        if (problema != null) throw new IllegalStateException(problema);

        String cliente = (nombre == null || nombre.isBlank()) ? CONSUMIDOR_FINAL : nombre.trim();
        String rtnLimpio = rtn == null ? "" : rtn.replaceAll("[\\s-]", "");
        if (!rtnLimpio.isEmpty() && !rtnLimpio.matches("\\d{14}"))
            throw new IllegalArgumentException("El RTN debe tener 14 dígitos");

        int total;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT total_cent FROM ventas WHERE id_venta = ? AND estado = 'Completada'")) {
            ps.setInt(1, idVenta);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("La venta no está cobrada");
                total = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM facturas_venta WHERE id_venta = ? AND anulada = 0")) {
            ps.setInt(1, idVenta);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new IllegalStateException("Esa venta ya tiene factura");
            }
        }

        String numero = cfg.numero(cfg.siguiente());
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM facturas_venta WHERE numero = ?")) {
            ps.setString(1, numero);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new IllegalStateException("El número " + numero
                        + " ya fue usado: corrige el siguiente número en Facturas › Configuración del CAI");
            }
        }

        int isv = isvIncluido(total, cfg.isvX100());
        String sql = "INSERT INTO facturas_venta (id_venta, numero, cai, rango_desde, rango_hasta, fecha_limite, "
                + "rtn_cliente, nombre_cliente, subtotal_cent, impuesto_cent, impuesto_pct_x100, total_cent, id_usuario) "
                + "VALUES (?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idVenta);
            ps.setString(2, numero);
            ps.setString(3, cfg.cai());
            ps.setString(4, cfg.numero(cfg.desde()));
            ps.setString(5, cfg.numero(cfg.hasta()));
            ps.setString(6, cfg.limite());
            ps.setString(7, rtnLimpio);
            ps.setString(8, cliente);
            ps.setInt(9, total - isv);
            ps.setInt(10, isv);
            ps.setInt(11, cfg.isvX100());
            ps.setInt(12, total);
            ps.setInt(13, idUsuario);
            ps.executeUpdate();
        }
        int id;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            id = rs.getInt(1);
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE config SET valor = ? WHERE clave = 'factura_siguiente'")) {
            ps.setString(1, String.valueOf(cfg.siguiente() + 1));
            ps.executeUpdate();
        }
        return id;
    }

    /** Emite la factura de una venta cobrada que quedó sin factura (ventas viejas o facturas anuladas). */
    public int emitirParaVenta(int idVenta, int idUsuario, String nombre, String rtn) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                int id = emitir(c, idVenta, idUsuario, nombre, rtn);
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo emitir la factura", e);
        }
    }

    /**
     * El cliente pidió factura con RTN o datos DESPUÉS de emitida: se anula la factura y se emite otra
     * (la ley no permite editar una factura emitida). Todo en una transacción: si no hay CAI para la nueva, no se anula nada.
     * soloTurnoAbierto = true para el cajero: solo puede cambiar facturas de ventas del turno que está abierto.
     */
    public int reemitir(int idFactura, int idUsuario, String nombre, String rtn, boolean soloTurnoAbierto) {
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                String sql = "SELECT f.id_venta FROM facturas_venta f JOIN ventas v ON v.id_venta = f.id_venta "
                        + "WHERE f.id_factura_venta = ? AND f.anulada = 0"
                        + (soloTurnoAbierto
                        ? " AND v.id_turno = (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta')" : "");
                int idVenta;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, idFactura);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("Esa factura ya no se puede cambiar");
                        idVenta = rs.getInt(1);
                    }
                }
                anularEn(c, idFactura, idUsuario, "El cliente pidió la factura con RTN o datos");
                int nueva = emitir(c, idVenta, idUsuario, nombre, rtn);
                c.commit();
                return nueva;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo cambiar la factura", e);
        }
    }

    /** Anula (no borra): queda en el historial y su número no se reutiliza. */
    public void anular(int idFactura, int idUsuario, String motivo) {
        try (Connection c = DatabaseConnection.getConnection()) {
            anularEn(c, idFactura, idUsuario, motivo);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo anular la factura", e);
        }
    }

    private static void anularEn(Connection c, int idFactura, int idUsuario, String motivo) throws SQLException {
        if (motivo == null || motivo.isBlank()) throw new IllegalArgumentException("Escribe el motivo de la anulación");
        String sql = "UPDATE facturas_venta SET anulada = 1, motivo_anulacion = ?, id_usuario_anulo = ?, "
                + "anulada_en = datetime('now','localtime') WHERE id_factura_venta = ? AND anulada = 0";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, motivo.trim());
            ps.setInt(2, idUsuario);
            ps.setInt(3, idFactura);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("La factura ya está anulada");
        }
    }

    // ---------- Consultas ----------

    /** estado: "V" solo vigentes, "A" solo anuladas, otro = todas. Las más recientes primero (máximo 500). */
    public List<Factura> listar(String estado, String texto) {
        StringBuilder sql = new StringBuilder(BASE).append("WHERE 1 = 1");
        if ("V".equals(estado)) sql.append(" AND f.anulada = 0");
        else if ("A".equals(estado)) sql.append(" AND f.anulada = 1");
        boolean filtra = texto != null && !texto.isBlank();
        if (filtra) {
            sql.append(" AND (f.numero LIKE ? OR f.nombre_cliente LIKE ? OR COALESCE(f.rtn_cliente, '') LIKE ?)");
        }
        sql.append(" ORDER BY f.id_factura_venta DESC LIMIT 500");
        List<Factura> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            if (filtra) {
                String patron = "%" + texto.trim() + "%";
                ps.setString(1, patron);
                ps.setString(2, patron);
                ps.setString(3, patron);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapear(rs, List.of()));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las facturas", e);
        }
    }

    /** Una factura con sus productos, o null si no existe. */
    public Factura factura(int idFactura) {
        try (Connection c = DatabaseConnection.getConnection();
             PreparedStatement ps = c.prepareStatement(BASE + "WHERE f.id_factura_venta = ?")) {
            ps.setInt(1, idFactura);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int idVenta = rs.getInt(2);
                Factura sinLineas = mapear(rs, List.of());
                return new Factura(sinLineas.id(), sinLineas.idVenta(), sinLineas.numero(), sinLineas.cai(),
                        sinLineas.rangoDesde(), sinLineas.rangoHasta(), sinLineas.fechaLimite(), sinLineas.rtn(),
                        sinLineas.cliente(), sinLineas.subtotalCent(), sinLineas.impuestoCent(),
                        sinLineas.impuestoPctX100(), sinLineas.totalCent(), sinLineas.atendio(),
                        sinLineas.emitidaEn(), sinLineas.anulada(), sinLineas.motivo(), sinLineas.metodoPago(),
                        sinLineas.recibidoCent(), sinLineas.cambioCent(), sinLineas.comanda(),
                        lineasDe(c, idVenta));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la factura", e);
        }
    }

    /** true si la factura es de una venta cobrada en el turno que está abierto ahora (lo único que ve el cajero). */
    public boolean esDelTurnoAbierto(int idFactura) {
        String sql = "SELECT 1 FROM facturas_venta f JOIN ventas v ON v.id_venta = f.id_venta "
                + "WHERE f.id_factura_venta = ? AND v.id_turno = (SELECT id_turno FROM turnos_caja WHERE estado = 'Abierta')";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idFactura);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la factura", e);
        }
    }

    /** Ventas cobradas sin factura vigente, la más reciente primero (máximo 200), con sus productos. */
    public List<VentaSinFactura> ventasSinFactura() {
        String sql = "SELECT v.id_venta, v.nombre_comanda, COALESCE(v.metodo_pago, ''), v.total_cent, "
                + "COALESCE(v.cobrada_en, '') FROM ventas v WHERE v.estado = 'Completada' "
                + "AND NOT EXISTS (SELECT 1 FROM facturas_venta f WHERE f.id_venta = v.id_venta AND f.anulada = 0) "
                + "ORDER BY v.id_venta DESC LIMIT 200";
        List<VentaSinFactura> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            List<int[]> ids = new ArrayList<>();
            List<String[]> textos = new ArrayList<>();
            while (rs.next()) {
                ids.add(new int[]{rs.getInt(1), rs.getInt(4)});
                textos.add(new String[]{rs.getString(2), rs.getString(3), rs.getString(5)});
            }
            for (int i = 0; i < ids.size(); i++) {
                lista.add(new VentaSinFactura(ids.get(i)[0], textos.get(i)[0], textos.get(i)[1], ids.get(i)[1],
                        textos.get(i)[2], lineasDe(c, ids.get(i)[0])));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las ventas sin factura", e);
        }
    }

    /** {total facturado hoy, ISV de hoy, facturas vigentes de hoy, ventas cobradas sin factura}. */
    public long[] resumenHoy() {
        try (Connection c = DatabaseConnection.getConnection(); Statement s = c.createStatement()) {
            long[] r = new long[4];
            try (ResultSet rs = s.executeQuery("SELECT COALESCE(SUM(total_cent), 0), COALESCE(SUM(impuesto_cent), 0), "
                    + "COUNT(*) FROM facturas_venta WHERE anulada = 0 AND date(emitida_en) = date('now','localtime')")) {
                rs.next();
                r[0] = rs.getLong(1);
                r[1] = rs.getLong(2);
                r[2] = rs.getLong(3);
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM ventas v WHERE v.estado = 'Completada' "
                    + "AND NOT EXISTS (SELECT 1 FROM facturas_venta f WHERE f.id_venta = v.id_venta AND f.anulada = 0)")) {
                rs.next();
                r[3] = rs.getLong(1);
            }
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el resumen de facturas", e);
        }
    }

    // ---------- Configuración del CAI y del negocio ----------

    public ConfigFactura config() {
        try (Connection c = DatabaseConnection.getConnection()) {
            return leerConfig(c);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la configuración de facturas", e);
        }
    }

    /** Valida y guarda la autorización del SAR y los datos del negocio. */
    public void guardarConfig(ConfigFactura n) {
        if (n.cai().isBlank()) throw new IllegalArgumentException("Escribe el CAI");
        if (n.desde() <= 0 || n.hasta() < n.desde())
            throw new IllegalArgumentException("Revisa el rango: \"desde\" no puede ser mayor que \"hasta\"");
        if (!n.limite().matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("La fecha límite debe ser AAAA-MM-DD");
        try {
            LocalDate.parse(n.limite());
        } catch (Exception e) {
            throw new IllegalArgumentException("La fecha límite no es una fecha válida");
        }
        if (n.siguiente() < n.desde() || n.siguiente() > n.hasta() + 1)
            throw new IllegalArgumentException("El siguiente número debe estar dentro del rango");
        if (!n.prefijo().matches("\\d{3}-\\d{3}-\\d{2}"))
            throw new IllegalArgumentException("El prefijo debe verse así: 000-001-01");
        if (n.negocio().isBlank()) throw new IllegalArgumentException("Escribe el nombre del negocio");
        if (!n.rtn().matches("\\d{14}")) throw new IllegalArgumentException("El RTN del negocio debe tener 14 dígitos");
        if (n.isvX100() < 0 || n.isvX100() > 10000) throw new IllegalArgumentException("El ISV debe estar entre 0 y 100");

        Map<String, String> valores = new HashMap<>();
        valores.put("factura_cai", n.cai());
        valores.put("factura_rango_desde", String.valueOf(n.desde()));
        valores.put("factura_rango_hasta", String.valueOf(n.hasta()));
        valores.put("factura_fecha_limite", n.limite());
        valores.put("factura_siguiente", String.valueOf(n.siguiente()));
        valores.put("factura_prefijo", n.prefijo());
        valores.put("impuesto_pct", porcentajeTexto(n.isvX100()));
        valores.put("nombre_negocio", n.negocio());
        valores.put("negocio_rtn", n.rtn());
        valores.put("negocio_direccion", n.direccion());
        valores.put("negocio_telefono", n.telefono());
        valores.put("negocio_email", n.email());
        valores.put("factura_imprimir_auto", n.imprimirAuto() ? "1" : "0");

        String sql = "INSERT INTO config (clave, valor) VALUES (?, ?) "
                + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor";
        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Map.Entry<String, String> e : valores.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.executeUpdate();
                }
                sincronizarAutorizacion(c, n);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la configuración", e);
        }
    }

    // ---------- Historial de autorizaciones (CAI) y compra de talonarios ----------

    /** Todas las autorizaciones registradas, la más reciente primero, con cuántas facturas se emitieron con cada una. */
    public List<AutorizacionCai> autorizaciones() {
        String sql = "SELECT a.id_cai, a.cai, a.prefijo, a.rango_desde, a.rango_hasta, a.fecha_limite, "
                + "COALESCE(a.fecha_compra, ''), a.imprenta, a.costo_cent, a.nota, COALESCE(u.nombre, ''), a.registrado_en, "
                + "(SELECT COUNT(*) FROM facturas_venta f WHERE a.cai = f.cai), "
                + "(SELECT COUNT(*) FROM facturas_venta f WHERE a.cai = f.cai AND f.anulada = 1), "
                + "COALESCE((SELECT MIN(f.numero) FROM facturas_venta f WHERE a.cai = f.cai), ''), "
                + "COALESCE((SELECT MAX(f.numero) FROM facturas_venta f WHERE a.cai = f.cai), '') "
                + "FROM autorizaciones_cai a LEFT JOIN usuarios u ON u.id_usuario = a.id_usuario ORDER BY a.id_cai DESC";
        List<AutorizacionCai> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new AutorizacionCai(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getInt(13), rs.getInt(14), rs.getString(15), rs.getString(16)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer el historial de CAI", e);
        }
        return lista;
    }

    /**
     * Registra la compra de un talonario nuevo (con su CAI) y lo deja como el CAI vigente: el siguiente número
     * pasa a ser el primero del rango. El CAI anterior se queda en el historial. Todo en una transacción.
     * Los mensajes de IllegalArgumentException están pensados para mostrarse tal cual.
     */
    public int registrarAutorizacion(String cai, String prefijo, int desde, int hasta, String limite, String fechaCompra,
                                     String imprenta, int costoCent, String nota, int idUsuario) {
        String caiLimpio = cai == null ? "" : cai.trim().toUpperCase();
        if (caiLimpio.isEmpty() || caiLimpio.length() > 60) throw new IllegalArgumentException("Escribe el CAI (máximo 60 caracteres)");
        if (prefijo == null || !prefijo.trim().matches("\\d{3}-\\d{3}-\\d{2}"))
            throw new IllegalArgumentException("El prefijo debe verse así: 000-001-01");
        if (desde <= 0 || hasta < desde)
            throw new IllegalArgumentException("Revisa el rango: \"desde\" no puede ser mayor que \"hasta\"");
        if (limite == null || !limite.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("La fecha límite debe ser AAAA-MM-DD");
        if (fechaCompra == null || !fechaCompra.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("La fecha de compra debe ser AAAA-MM-DD");
        try {
            if (LocalDate.parse(limite).isBefore(LocalDate.now()))
                throw new IllegalArgumentException("La fecha límite ya pasó: revisa el CAI");
            LocalDate.parse(fechaCompra);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Una de las fechas no es válida");
        }
        if (costoCent < 0 || costoCent > 100_000_000) throw new IllegalArgumentException("Revisa el costo de la compra");
        String imp = imprenta == null ? "" : imprenta.trim();
        String not = nota == null ? "" : nota.trim();
        if (imp.length() > 80 || not.length() > 120) throw new IllegalArgumentException("La imprenta o la nota son muy largas");
        String pref = prefijo.trim();

        try (Connection c = DatabaseConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT cai FROM autorizaciones_cai WHERE cai = ?")) {
                    ps.setString(1, caiLimpio);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new IllegalArgumentException("Ese CAI ya está registrado");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT cai FROM autorizaciones_cai WHERE prefijo = ? AND rango_desde <= ? AND rango_hasta >= ? LIMIT 1")) {
                    ps.setString(1, pref);
                    ps.setInt(2, hasta);
                    ps.setInt(3, desde);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new IllegalArgumentException("Ese rango se cruza con el del CAI " + rs.getString(1));
                    }
                }
                int id;
                String ins = "INSERT INTO autorizaciones_cai (cai, prefijo, rango_desde, rango_hasta, fecha_limite, fecha_compra, "
                        + "imprenta, costo_cent, nota, id_usuario) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, caiLimpio);
                    ps.setString(2, pref);
                    ps.setInt(3, desde);
                    ps.setInt(4, hasta);
                    ps.setString(5, limite);
                    ps.setString(6, fechaCompra);
                    ps.setString(7, imp);
                    ps.setInt(8, costoCent);
                    ps.setString(9, not);
                    ps.setInt(10, idUsuario);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
                ponerConfig(c, "factura_cai", caiLimpio);
                ponerConfig(c, "factura_prefijo", pref);
                ponerConfig(c, "factura_rango_desde", String.valueOf(desde));
                ponerConfig(c, "factura_rango_hasta", String.valueOf(hasta));
                ponerConfig(c, "factura_fecha_limite", limite);
                ponerConfig(c, "factura_siguiente", String.valueOf(desde));
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar el CAI", e);
        }
    }

    /** Si el CAI de la configuración no está en el historial lo agrega; si ya está, actualiza su rango y fecha. */
    private static void sincronizarAutorizacion(Connection c, ConfigFactura n) throws SQLException {
        String cai = n.cai().trim().toUpperCase();
        try (PreparedStatement ps = c.prepareStatement("UPDATE autorizaciones_cai SET prefijo = ?, rango_desde = ?, "
                + "rango_hasta = ?, fecha_limite = ? WHERE cai = ?")) {
            ps.setString(1, n.prefijo());
            ps.setInt(2, n.desde());
            ps.setInt(3, n.hasta());
            ps.setString(4, n.limite());
            ps.setString(5, cai);
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO autorizaciones_cai (cai, prefijo, rango_desde, rango_hasta, "
                + "fecha_limite) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, cai);
            ps.setString(2, n.prefijo());
            ps.setInt(3, n.desde());
            ps.setInt(4, n.hasta());
            ps.setString(5, n.limite());
            ps.executeUpdate();
        }
    }

    private static void ponerConfig(Connection c, String clave, String valor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO config (clave, valor) VALUES (?, ?) "
                + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor")) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.executeUpdate();
        }
    }

    // ---------- Facturas de compra (respaldo de lo que se compra a proveedores) ----------

    public List<FacturaCompra> compras() {
        String sql = "SELECT fc.id_factura_compra, p.nombre, fc.numero, fc.fecha, COALESCE(fc.nota, ''), fc.total_cent "
                + "FROM facturas_compra fc JOIN proveedores p ON p.id_proveedor = fc.id_proveedor "
                + "ORDER BY fc.fecha DESC, fc.id_factura_compra DESC LIMIT 300";
        List<FacturaCompra> lista = new ArrayList<>();
        try (Connection c = DatabaseConnection.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                lista.add(new FacturaCompra(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6)));
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron leer las facturas de compra", e);
        }
    }

    public void registrarCompra(int idProveedor, String numero, String fecha, int totalCent, String nota) {
        if (idProveedor <= 0) throw new IllegalArgumentException("Elige un proveedor");
        if (numero == null || numero.isBlank()) throw new IllegalArgumentException("Escribe el número de la factura");
        if (fecha == null || !fecha.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("La fecha debe ser AAAA-MM-DD");
        if (totalCent < 0) throw new IllegalArgumentException("El total no puede ser negativo");
        String sql = "INSERT INTO facturas_compra (id_proveedor, numero, fecha, total_cent, nota) "
                + "VALUES (?, ?, ?, ?, NULLIF(?, ''))";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idProveedor);
            ps.setString(2, numero.trim());
            ps.setString(3, fecha);
            ps.setInt(4, totalCent);
            ps.setString(5, nota == null ? "" : nota.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("UNIQUE"))
                throw new IllegalArgumentException("Esa factura de ese proveedor ya está registrada");
            throw new IllegalStateException("No se pudo registrar la factura de compra", e);
        }
    }

    // ---------- interno ----------

    /** ISV que ya viene incluido en un total (centavos). Con 15 %: L 24.00 → L 3.13 de ISV y L 20.87 de subtotal. */
    static int isvIncluido(int totalCent, int pctX100) {
        long base = 10000L + pctX100;
        long sinIsv = (totalCent * 10000L + base / 2) / base;
        return (int) (totalCent - sinIsv);
    }

    static ConfigFactura leerConfig(Connection c) throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT clave, valor FROM config")) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        }
        return new ConfigFactura(
                m.getOrDefault("factura_cai", "").trim(),
                entero(m.get("factura_rango_desde")),
                entero(m.get("factura_rango_hasta")),
                m.getOrDefault("factura_fecha_limite", "").trim(),
                entero(m.get("factura_siguiente")),
                m.getOrDefault("factura_prefijo", "").trim(),
                porcentajeX100(m.get("impuesto_pct")),
                m.getOrDefault("nombre_negocio", "").trim(),
                m.getOrDefault("negocio_rtn", "").trim(),
                m.getOrDefault("negocio_direccion", "").trim(),
                m.getOrDefault("negocio_telefono", "").trim(),
                m.getOrDefault("negocio_email", "").trim(),
                "1".equals(m.get("factura_imprimir_auto")));
    }

    private static List<LineaFactura> lineasDe(Connection c, int idVenta) throws SQLException {
        String sql = "SELECT p.nombre, d.cantidad, d.precio_unit_cent, d.subtotal_cent FROM detalle_ventas d "
                + "JOIN productos p ON p.id_producto = d.id_producto WHERE d.id_venta = ? ORDER BY d.id_detalle";
        List<LineaFactura> lineas = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, idVenta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lineas.add(new LineaFactura(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
        }
        return lineas;
    }

    private static Factura mapear(ResultSet rs, List<LineaFactura> lineas) throws SQLException {
        return new Factura(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getInt(11),
                rs.getInt(12), rs.getInt(13), rs.getString(14), rs.getString(15), rs.getInt(16) == 1,
                rs.getString(17), rs.getString(18), rs.getInt(19), rs.getInt(20), rs.getString(21), lineas);
    }

    private static int entero(String texto) {
        try {
            return texto == null || texto.isBlank() ? 0 : Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "15" → 1500, "15.5" → 1550. Si el texto no es un número, 0. */
    private static int porcentajeX100(String texto) {
        try {
            return texto == null || texto.isBlank() ? 0
                    : (int) Math.round(Double.parseDouble(texto.trim().replace(',', '.')) * 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 1500 → "15", 1550 → "15.5". */
    private static String porcentajeTexto(int x100) {
        return x100 % 100 == 0 ? String.valueOf(x100 / 100) : String.valueOf(x100 / 100.0);
    }
}
