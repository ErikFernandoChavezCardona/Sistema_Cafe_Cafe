package application.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Crea un archivo de Excel (.xlsx) con UNA tabla de Excel (filtros en el encabezado, filas alternadas) a partir de
 * columnas y filas cualquiera. Lo usan los reportes (ventas por día, productos, turnos de caja y libro de ventas).
 * Las fechas y el dinero van como valores reales (se pueden ordenar, filtrar y sumar) y los totales llevan fórmulas.
 * No usa librerías externas: un .xlsx es un ZIP de archivos XML y aquí se escriben a mano.
 */
public final class ExcelTabla {

    /** FECHA = "AAAA-MM-DD"; FECHAHORA = "AAAA-MM-DD HH:MM:SS"; DINERO llega en centavos; PORCENTAJE llega como fracción (0.35 = 35 %). */
    public enum Tipo {TEXTO, FECHA, FECHAHORA, ENTERO, DINERO, PORCENTAJE}

    /** Una columna. Si sumar = true, debajo de la tabla se pone el total de esa columna (con fórmula SUM). */
    public record Col(String nombre, double ancho, Tipo tipo, boolean sumar) {
        public Col(String nombre, double ancho, Tipo tipo) {
            this(nombre, ancho, tipo, false);
        }
    }

    private static final int FILA_ENCABEZADO = 4;

    // Índices de estilo (posición en <cellXfs> de styles.xml)
    private static final int S_TITULO = 1, S_SUBTITULO = 2, S_ENCAB = 3, S_TEXTO = 4, S_FECHA = 5, S_FECHAHORA = 6, S_ENTERO = 7,
            S_DINERO = 8, S_PORCENTAJE = 9, S_TOT_ETIQ = 10, S_TOT_ENTERO = 11, S_TOT_DINERO = 12;

    private final Map<String, Integer> textos = new LinkedHashMap<>();   // tabla de textos compartidos
    private int totalReferencias = 0;

    private ExcelTabla() {
    }

    /**
     * @param hoja      nombre de la hoja (máximo 31 letras, sin / \ ? * [ ])
     * @param titulo    primera línea grande
     * @param subtitulo segunda línea pequeña (por ejemplo el período)
     * @param filas     cada fila trae un valor por columna: String (texto y fechas), número (entero, dinero en centavos,
     *                  porcentaje como fracción) o null (celda vacía). Debe haber al menos una fila.
     */
    public static byte[] generar(String hoja, String titulo, String subtitulo, List<Col> cols, List<Object[]> filas) throws IOException {
        if (filas.isEmpty()) throw new IllegalArgumentException("No hay filas para exportar");
        return new ExcelTabla().crear(hoja, titulo, subtitulo, cols, filas);
    }

    private byte[] crear(String hoja, String titulo, String subtitulo, List<Col> cols, List<Object[]> filas) throws IOException {
        String xmlHoja = hoja(titulo, subtitulo, cols, filas);   // primero la hoja: así se arma la tabla de textos
        int ultima = FILA_ENCABEZADO + filas.size();

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida, StandardCharsets.UTF_8)) {
            archivo(zip, "[Content_Types].xml", CONTENT_TYPES);
            archivo(zip, "_rels/.rels", RELS_RAIZ);
            archivo(zip, "xl/workbook.xml", libro(hoja));
            archivo(zip, "xl/_rels/workbook.xml.rels", RELS_LIBRO);
            archivo(zip, "xl/styles.xml", ESTILOS);
            archivo(zip, "xl/sharedStrings.xml", textosCompartidos());
            archivo(zip, "xl/worksheets/sheet1.xml", xmlHoja);
            archivo(zip, "xl/worksheets/_rels/sheet1.xml.rels", RELS_HOJA);
            archivo(zip, "xl/tables/table1.xml", tabla(cols, ultima));
        }
        return salida.toByteArray();
    }

    // ---------- Hoja ----------

    private String hoja(String titulo, String subtitulo, List<Col> cols, List<Object[]> filas) {
        int primera = FILA_ENCABEZADO + 1;
        int ultima = FILA_ENCABEZADO + filas.size();
        StringBuilder x = new StringBuilder(XML);
        x.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\">")
                .append("<pane ySplit=\"").append(FILA_ENCABEZADO).append("\" topLeftCell=\"A").append(primera)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\"/></sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"18\"/><cols>");
        for (int i = 0; i < cols.size(); i++) {
            x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(cols.get(i).ancho())
                    .append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");

        x.append("<row r=\"1\" ht=\"28\" customHeight=\"1\">").append(texto("A1", titulo, S_TITULO)).append("</row>");
        x.append("<row r=\"2\">").append(texto("A2", subtitulo, S_SUBTITULO)).append("</row>");

        x.append("<row r=\"").append(FILA_ENCABEZADO).append("\" ht=\"26\" customHeight=\"1\">");
        for (int c = 0; c < cols.size(); c++) x.append(texto(ref(c, FILA_ENCABEZADO), cols.get(c).nombre(), S_ENCAB));
        x.append("</row>");

        long[] sumas = new long[cols.size()];
        int fila = primera;
        for (Object[] f : filas) {
            x.append("<row r=\"").append(fila).append("\">");
            for (int c = 0; c < cols.size(); c++) {
                Object v = c < f.length ? f[c] : null;
                x.append(celda(ref(c, fila), cols.get(c).tipo(), v));
                if (cols.get(c).sumar() && v instanceof Number n) sumas[c] += n.longValue();
            }
            x.append("</row>");
            fila++;
        }

        // Totales debajo de la tabla (una fila en blanco de por medio). Las fórmulas guardan también su resultado para que se vean en cualquier visor.
        boolean haySumas = false;
        for (Col c : cols) haySumas |= c.sumar();
        if (haySumas) {
            int t = ultima + 2;
            x.append("<row r=\"").append(t).append("\">").append(texto("A" + t, "Total", S_TOT_ETIQ));
            for (int c = 0; c < cols.size(); c++) {
                if (!cols.get(c).sumar()) continue;
                boolean dinero = cols.get(c).tipo() == Tipo.DINERO;
                String letra = letra(c);
                x.append("<c r=\"").append(letra).append(t).append("\" s=\"").append(dinero ? S_TOT_DINERO : S_TOT_ENTERO)
                        .append("\"><f>SUM(").append(letra).append(primera).append(':').append(letra).append(ultima)
                        .append(")</f><v>").append(dinero ? lempiras(sumas[c]) : String.valueOf(sumas[c])).append("</v></c>");
            }
            x.append("</row>");
        }

        x.append("</sheetData>")
                .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
                .append("<tableParts count=\"1\"><tablePart r:id=\"rId1\"/></tableParts></worksheet>");
        return x.toString();
    }

    private String tabla(List<Col> cols, int ultima) {
        StringBuilder x = new StringBuilder(XML);
        String rango = "A" + FILA_ENCABEZADO + ":" + letra(cols.size() - 1) + ultima;
        x.append("<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" id=\"1\" name=\"TablaReporte\" ")
                .append("displayName=\"TablaReporte\" ref=\"").append(rango).append("\" totalsRowShown=\"0\">")
                .append("<autoFilter ref=\"").append(rango).append("\"/><tableColumns count=\"").append(cols.size()).append("\">");
        for (int i = 0; i < cols.size(); i++) {
            x.append("<tableColumn id=\"").append(i + 1).append("\" name=\"").append(esc(cols.get(i).nombre())).append("\"/>");
        }
        return x.append("</tableColumns><tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" ")
                .append("showRowStripes=\"1\" showColumnStripes=\"0\"/></table>").toString();
    }

    // ---------- Celdas ----------

    private String celda(String ref, Tipo tipo, Object v) {
        switch (tipo) {
            case TEXTO:
                return texto(ref, v == null ? null : v.toString(), S_TEXTO);
            case FECHA:
            case FECHAHORA: {
                int estilo = tipo == Tipo.FECHA ? S_FECHA : S_FECHAHORA;
                if (v == null) return vacia(ref, estilo);
                String s = v.toString();
                try {
                    LocalDateTime dt = s.length() > 10 ? LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T')) : LocalDate.parse(s).atStartOfDay();
                    double dias = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), dt.toLocalDate()) + dt.toLocalTime().toSecondOfDay() / 86400.0;
                    return numero(ref, String.format(Locale.ROOT, "%.10f", dias + 1e-9), estilo);
                } catch (Exception e) {
                    return texto(ref, s, S_TEXTO);   // si la fecha viene rara, se guarda como texto en vez de perderla
                }
            }
            case ENTERO:
                return v instanceof Number n ? numero(ref, String.valueOf(n.longValue()), S_ENTERO) : vacia(ref, S_ENTERO);
            case DINERO:
                return v instanceof Number n ? numero(ref, lempiras(n.longValue()), S_DINERO) : vacia(ref, S_DINERO);
            default:
                return v instanceof Number n ? numero(ref, String.format(Locale.ROOT, "%.6f", n.doubleValue()), S_PORCENTAJE) : vacia(ref, S_PORCENTAJE);
        }
    }

    private String texto(String ref, String valor, int estilo) {
        if (valor == null || valor.isEmpty()) return vacia(ref, estilo);
        Integer i = textos.get(valor);
        if (i == null) {
            i = textos.size();
            textos.put(valor, i);
        }
        totalReferencias++;
        return "<c r=\"" + ref + "\" s=\"" + estilo + "\" t=\"s\"><v>" + i + "</v></c>";
    }

    private static String numero(String ref, String valor, int estilo) {
        return "<c r=\"" + ref + "\" s=\"" + estilo + "\"><v>" + valor + "</v></c>";
    }

    private static String vacia(String ref, int estilo) {
        return "<c r=\"" + ref + "\" s=\"" + estilo + "\"/>";
    }

    /** Centavos a lempiras con dos decimales ("-12.50" si es negativo). */
    private static String lempiras(long centavos) {
        long a = Math.abs(centavos);
        return (centavos < 0 ? "-" : "") + String.format(Locale.ROOT, "%d.%02d", a / 100, a % 100);
    }

    private static String letra(int columna) {
        return String.valueOf((char) ('A' + columna));
    }

    private static String ref(int columna, int fila) {
        return letra(columna) + fila;
    }

    private String textosCompartidos() {
        StringBuilder x = new StringBuilder(XML);
        x.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"").append(totalReferencias)
                .append("\" uniqueCount=\"").append(textos.size()).append("\">");
        for (String t : textos.keySet()) x.append("<si><t xml:space=\"preserve\">").append(esc(t)).append("</t></si>");
        return x.append("</sst>").toString();
    }

    private static String libro(String hoja) {
        return XML
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews>"
                + "<sheets><sheet name=\"" + esc(hoja) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    /** Escapa lo que no puede ir tal cual dentro de un XML y quita caracteres de control inválidos. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static void archivo(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ---------- Partes fijas del archivo ----------

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private static final String CONTENT_TYPES = XML
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
            + "<Override PartName=\"/xl/tables/table1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>"
            + "</Types>";

    private static final String RELS_RAIZ = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String RELS_LIBRO = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
            + "</Relationships>";

    private static final String RELS_HOJA = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table1.xml\"/>"
            + "</Relationships>";

    /** Fuentes, rellenos, bordes y formatos. El orden de <xf> = números S_* de arriba. */
    private static final String ESTILOS = XML
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"4\"><numFmt numFmtId=\"167\" formatCode=\"dd/mm/yyyy\"/><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\\ hh:mm\"/>"
            + "<numFmt numFmtId=\"165\" formatCode=\"&quot;L&quot;\\ #,##0.00;[Red]\\-&quot;L&quot;\\ #,##0.00\"/>"
            + "<numFmt numFmtId=\"166\" formatCode=\"0.0%\"/></numFmts>"
            + "<fonts count=\"5\">"
            + "<font><sz val=\"11\"/><color rgb=\"FF10222F\"/><name val=\"Calibri\"/></font>"                                  // 0 normal
            + "<font><b/><sz val=\"16\"/><color rgb=\"FF1B5A85\"/><name val=\"Calibri\"/></font>"                              // 1 título
            + "<font><sz val=\"10\"/><color rgb=\"FF5B6F7D\"/><name val=\"Calibri\"/></font>"                                  // 2 subtítulo
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"                              // 3 encabezado
            + "<font><b/><sz val=\"11\"/><color rgb=\"FF1B5A85\"/><name val=\"Calibri\"/></font>"                              // 4 totales
            + "</fonts>"
            + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1B5A85\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCEBF4\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFD0D7DE\"/></left><right style=\"thin\"><color rgb=\"FFD0D7DE\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFD0D7DE\"/></top><bottom style=\"thin\"><color rgb=\"FFD0D7DE\"/></bottom><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"13\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"                                                                   // 0
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"><alignment vertical=\"center\"/></xf>"               // 1 título
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"                                                  // 2 subtítulo
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"                                                                 // 3 encabezado
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"       // 4 texto
            + "<xf numFmtId=\"167\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"center\"/></xf>"                                                                                  // 5 fecha
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"center\"/></xf>"                                                                                  // 6 fecha y hora
            + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\" vertical=\"center\"/></xf>"                                                                                 // 7 entero
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\" vertical=\"center\"/></xf>"                                                                                 // 8 dinero
            + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\" vertical=\"center\"/></xf>"                                                                                 // 9 porcentaje
            + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"                  // 10 etiqueta total
            + "<xf numFmtId=\"3\" fontId=\"4\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\"/></xf>"                                                                                                       // 11 total entero
            + "<xf numFmtId=\"165\" fontId=\"4\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\"/></xf>"                                                                                                       // 12 total dinero
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
