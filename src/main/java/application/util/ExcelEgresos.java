package application.util;

import application.model.entity.EgresoFila;

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
 * Crea el archivo de Excel (.xlsx) de los egresos, con una TABLA de Excel (filtros en el encabezado, filas
 * alternadas), fechas y montos como valores reales y totales con fórmulas.
 * No usa librerías externas: un .xlsx es un ZIP de archivos XML y aquí se escriben a mano.
 */
public final class ExcelEgresos {

    private static final String[] COLUMNAS = {"Fecha", "Descripción", "Categoría", "Origen", "Turno", "Registró",
            "Monto (L)", "Estado", "Motivo de anulación", "Anuló"};
    private static final double[] ANCHOS = {18, 38, 16, 15, 9, 24, 16, 12, 30, 24};
    private static final int FILA_ENCABEZADO = 4;

    // Índices de estilo (posición en <cellXfs> de styles.xml)
    private static final int S_TITULO = 1, S_SUBTITULO = 2, S_ENCAB = 3, S_TEXTO = 4, S_FECHA = 5, S_TURNO = 6, S_MONTO = 7,
            S_TEXTO_ANU = 8, S_FECHA_ANU = 9, S_TURNO_ANU = 10, S_MONTO_ANU = 11, S_TOT_ETIQ = 12, S_TOT_MONTO = 13, S_TOT_NUM = 14;

    private final Map<String, Integer> textos = new LinkedHashMap<>();   // tabla de textos compartidos
    private int totalReferencias = 0;

    private ExcelEgresos() {
    }

    /** Devuelve el contenido del archivo .xlsx listo para guardar. */
    public static byte[] generar(List<EgresoFila> filas, String desde, String hasta) throws IOException {
        return new ExcelEgresos().crear(filas, desde, hasta);
    }

    private byte[] crear(List<EgresoFila> filas, String desde, String hasta) throws IOException {
        String hoja = hoja(filas, desde, hasta);   // primero la hoja: así se arma la tabla de textos
        int ultima = FILA_ENCABEZADO + filas.size();

        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida, StandardCharsets.UTF_8)) {
            archivo(zip, "[Content_Types].xml", CONTENT_TYPES);
            archivo(zip, "_rels/.rels", RELS_RAIZ);
            archivo(zip, "xl/workbook.xml", LIBRO);
            archivo(zip, "xl/_rels/workbook.xml.rels", RELS_LIBRO);
            archivo(zip, "xl/styles.xml", ESTILOS);
            archivo(zip, "xl/sharedStrings.xml", textosCompartidos());
            archivo(zip, "xl/worksheets/sheet1.xml", hoja);
            archivo(zip, "xl/worksheets/_rels/sheet1.xml.rels", RELS_HOJA);
            archivo(zip, "xl/tables/table1.xml", tabla(ultima));
        }
        return salida.toByteArray();
    }

    // ---------- Hoja ----------

    private String hoja(List<EgresoFila> filas, String desde, String hasta) {
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
        for (int i = 0; i < ANCHOS.length; i++) {
            x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(ANCHOS[i])
                    .append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");

        x.append("<row r=\"1\" ht=\"28\" customHeight=\"1\">").append(texto("A1", "Café Café · Egresos", S_TITULO)).append("</row>");
        x.append("<row r=\"2\">").append(texto("A2", "Período: " + desde + " al " + hasta, S_SUBTITULO)).append("</row>");

        x.append("<row r=\"").append(FILA_ENCABEZADO).append("\" ht=\"26\" customHeight=\"1\">");
        for (int c = 0; c < COLUMNAS.length; c++) x.append(texto(ref(c, FILA_ENCABEZADO), COLUMNAS[c], S_ENCAB));
        x.append("</row>");

        long total = 0, caja = 0, fuera = 0;
        int anulados = 0;
        int fila = primera;
        for (EgresoFila e : filas) {
            boolean an = e.anulado();
            int sT = an ? S_TEXTO_ANU : S_TEXTO, sF = an ? S_FECHA_ANU : S_FECHA, sN = an ? S_TURNO_ANU : S_TURNO, sM = an ? S_MONTO_ANU : S_MONTO;
            x.append("<row r=\"").append(fila).append("\">")
                    .append(fecha(ref(0, fila), e.fecha(), sF))
                    .append(texto(ref(1, fila), e.descripcion(), sT))
                    .append(texto(ref(2, fila), e.categoria(), sT))
                    .append(texto(ref(3, fila), e.pagadoDesdeCaja() ? "Caja" : "Fuera de caja", sT))
                    .append(e.idTurno() == null ? vacia(ref(4, fila), sN) : numero(ref(4, fila), String.valueOf(e.idTurno()), sN))
                    .append(texto(ref(5, fila), e.usuario(), sT))
                    .append(numero(ref(6, fila), lempiras(e.montoCent()), sM))
                    .append(texto(ref(7, fila), an ? "Anulado" : "Vigente", sT))
                    .append(texto(ref(8, fila), e.motivoAnulacion(), sT))
                    .append(texto(ref(9, fila), e.anuladoPor(), sT))
                    .append("</row>");
            if (an) {
                anulados++;
            } else {
                total += e.montoCent();
                if (e.pagadoDesdeCaja()) caja += e.montoCent(); else fuera += e.montoCent();
            }
            fila++;
        }

        // Totales debajo de la tabla (los anulados no suman). Las fórmulas guardan también su resultado para que se vean en cualquier visor.
        String col = "G" + primera + ":G" + ultima, est = "H" + primera + ":H" + ultima, ori = "D" + primera + ":D" + ultima;
        int t = ultima + 2;
        x.append(filaTotal(t, "Total de egresos vigentes", "SUMIF(" + est + ",\"Vigente\"," + col + ")", lempiras(total), S_TOT_MONTO));
        x.append(filaTotal(t + 1, "Pagado desde la caja", "SUMIFS(" + col + "," + est + ",\"Vigente\"," + ori + ",\"Caja\")", lempiras(caja), S_TOT_MONTO));
        x.append(filaTotal(t + 2, "Pagado fuera de la caja", "SUMIFS(" + col + "," + est + ",\"Vigente\"," + ori + ",\"Fuera de caja\")", lempiras(fuera), S_TOT_MONTO));
        x.append(filaTotal(t + 3, "Egresos anulados (no suman)", "COUNTIF(" + est + ",\"Anulado\")", String.valueOf(anulados), S_TOT_NUM));

        x.append("</sheetData>")
                .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
                .append("<tableParts count=\"1\"><tablePart r:id=\"rId1\"/></tableParts></worksheet>");
        return x.toString();
    }

    private String filaTotal(int fila, String etiqueta, String formula, String valor, int estiloValor) {
        return "<row r=\"" + fila + "\">" + texto("F" + fila, etiqueta, S_TOT_ETIQ)
                + "<c r=\"G" + fila + "\" s=\"" + estiloValor + "\"><f>" + esc(formula) + "</f><v>" + valor + "</v></c></row>";
    }

    private String tabla(int ultima) {
        StringBuilder x = new StringBuilder(XML);
        String rango = "A" + FILA_ENCABEZADO + ":J" + ultima;
        x.append("<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" id=\"1\" name=\"TablaEgresos\" ")
                .append("displayName=\"TablaEgresos\" ref=\"").append(rango).append("\" totalsRowShown=\"0\">")
                .append("<autoFilter ref=\"").append(rango).append("\"/><tableColumns count=\"").append(COLUMNAS.length).append("\">");
        for (int i = 0; i < COLUMNAS.length; i++) {
            x.append("<tableColumn id=\"").append(i + 1).append("\" name=\"").append(esc(COLUMNAS[i])).append("\"/>");
        }
        return x.append("</tableColumns><tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" ")
                .append("showRowStripes=\"1\" showColumnStripes=\"0\"/></table>").toString();
    }

    // ---------- Celdas ----------

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

    /** La fecha "AAAA-MM-DD HH:MM:SS" pasa a número de serie de Excel (así se puede ordenar y filtrar como fecha). */
    private String fecha(String ref, String f, int estilo) {
        try {
            LocalDateTime dt = f.length() > 10 ? LocalDateTime.parse(f.substring(0, 19).replace(' ', 'T')) : LocalDate.parse(f).atStartOfDay();
            double dias = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), dt.toLocalDate()) + dt.toLocalTime().toSecondOfDay() / 86400.0;
            return numero(ref, String.format(Locale.ROOT, "%.10f", dias + 1e-9), estilo);
        } catch (Exception e) {
            return texto(ref, f, estilo == S_FECHA_ANU ? S_TEXTO_ANU : S_TEXTO);
        }
    }

    private static String lempiras(long centavos) {
        return String.format(Locale.ROOT, "%d.%02d", centavos / 100, centavos % 100);
    }

    private static String ref(int columna, int fila) {
        return (char) ('A' + columna) + String.valueOf(fila);
    }

    private String textosCompartidos() {
        StringBuilder x = new StringBuilder(XML);
        x.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"").append(totalReferencias)
                .append("\" uniqueCount=\"").append(textos.size()).append("\">");
        for (String t : textos.keySet()) x.append("<si><t xml:space=\"preserve\">").append(esc(t)).append("</t></si>");
        return x.append("</sst>").toString();
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

    private static final String LIBRO = XML
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews>"
            + "<sheets><sheet name=\"Egresos\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";

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
            + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\\ hh:mm\"/>"
            + "<numFmt numFmtId=\"165\" formatCode=\"&quot;L&quot;\\ #,##0.00\"/></numFmts>"
            + "<fonts count=\"6\">"
            + "<font><sz val=\"11\"/><color rgb=\"FF10222F\"/><name val=\"Calibri\"/></font>"                                  // 0 normal
            + "<font><b/><sz val=\"16\"/><color rgb=\"FF1B5A85\"/><name val=\"Calibri\"/></font>"                              // 1 título
            + "<font><sz val=\"10\"/><color rgb=\"FF5B6F7D\"/><name val=\"Calibri\"/></font>"                                  // 2 subtítulo
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"                              // 3 encabezado
            + "<font><strike/><sz val=\"11\"/><color rgb=\"FF8A949C\"/><name val=\"Calibri\"/></font>"                         // 4 anulado
            + "<font><b/><sz val=\"11\"/><color rgb=\"FF1B5A85\"/><name val=\"Calibri\"/></font>"                              // 5 totales
            + "</fonts>"
            + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1B5A85\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCEBF4\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFD0D7DE\"/></left><right style=\"thin\"><color rgb=\"FFD0D7DE\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFD0D7DE\"/></top><bottom style=\"thin\"><color rgb=\"FFD0D7DE\"/></bottom><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"15\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"                                                                   // 0
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"><alignment vertical=\"center\"/></xf>"               // 1 título
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"                                                  // 2 subtítulo
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"                                                                 // 3 encabezado
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"       // 4 texto
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"center\"/></xf>"                                                                                  // 5 fecha
            + "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\"/></xf>"                                                                                // 6 turno
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\" vertical=\"center\"/></xf>"                                                                                 // 7 monto
            + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"      // 8
            + "<xf numFmtId=\"164\" fontId=\"4\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"left\" vertical=\"center\"/></xf>"                                                                                  // 9
            + "<xf numFmtId=\"1\" fontId=\"4\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\"/></xf>"                                                                                // 10
            + "<xf numFmtId=\"165\" fontId=\"4\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\" vertical=\"center\"/></xf>"                                                                                 // 11
            + "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\"/></xf>"            // 12 etiqueta total
            + "<xf numFmtId=\"165\" fontId=\"5\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"  // 13 total L
            + "<xf numFmtId=\"1\" fontId=\"5\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"right\"/></xf>"                                                                                                       // 14 total número
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
