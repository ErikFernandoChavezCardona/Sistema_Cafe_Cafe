package application.util;

import application.util.ExcelTabla.Col;
import application.util.ExcelTabla.Tipo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

/**
 * Crea un PDF formal (sobrio, en blanco y negro, estilo documento corporativo) con una tabla.
 * Recibe lo mismo que ExcelTabla (columnas y filas), así cada reporte sale en Excel y en PDF con los mismos datos.
 * No usa librerías externas: escribe el PDF a mano con las fuentes estándar Helvetica.
 */
public final class PdfTabla {

    private static final Charset WIN = Charset.forName("windows-1252");
    private static final double[] ANCHO = { // anchos de Helvetica (por 1000) del carácter 32 al 126
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};

    private static final float MARGEN = 40, FILA = 17, TAM = 8.5f, TAM_ENC = 8.5f;

    private final List<ByteArrayOutputStream> paginas = new ArrayList<>();
    private StringBuilder pg;
    private float w, h, y;

    private PdfTabla() {
    }

    public static byte[] generar(String titulo, String subtitulo, List<Col> cols, List<Object[]> filas) throws IOException {
        return new PdfTabla().crear(titulo, subtitulo, cols, filas);
    }

    private byte[] crear(String titulo, String subtitulo, List<Col> cols, List<Object[]> filas) throws IOException {
        boolean ancha = cols.size() > 6;
        w = ancha ? 842 : 595;
        h = ancha ? 595 : 842;
        float util = w - 2 * MARGEN, suma = 0;
        for (Col c : cols) suma += c.ancho();
        float[] x = new float[cols.size() + 1];
        x[0] = MARGEN;
        for (int i = 0; i < cols.size(); i++) x[i + 1] = x[i] + (float) (cols.get(i).ancho() / suma * util);

        List<String[]> texto = new ArrayList<>();
        long[] tot = new long[cols.size()];
        for (Object[] f : filas) {
            String[] t = new String[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                t[i] = valor(cols.get(i).tipo(), f[i]);
                if (cols.get(i).sumar() && f[i] instanceof Number n) tot[i] += n.longValue();
            }
            texto.add(t);
        }
        boolean hayTotales = false;
        for (Col c : cols) hayTotales |= c.sumar();

        nuevaPagina(titulo, subtitulo, true);
        encabezadoTabla(cols, x);
        for (String[] t : texto) {
            if (y < MARGEN + 2 * FILA) {
                nuevaPagina(titulo, subtitulo, false);
                encabezadoTabla(cols, x);
            }
            fila(cols, x, t, false);
        }
        if (hayTotales) {
            if (y < MARGEN + 2 * FILA) {
                nuevaPagina(titulo, subtitulo, false);
                encabezadoTabla(cols, x);
            }
            String[] t = new String[cols.size()];
            for (int i = 0; i < t.length; i++) t[i] = cols.get(i).sumar() ? valor(cols.get(i).tipo(), tot[i]) : "";
            t[0] = "TOTAL";
            linea(x[0], y, x[x.length - 1], y, 0.9f);
            fila(cols, x, t, true);
            linea(x[0], y, x[x.length - 1], y, 0.9f);
        }
        cerrarPagina();
        return armar();
    }

    // ---------- Páginas ----------

    private void nuevaPagina(String titulo, String subtitulo, boolean primera) {
        if (pg != null) cerrarPagina();
        pg = new StringBuilder();
        y = h - MARGEN;
        texto(titulo, MARGEN, y - 12, 15, true, false);
        y -= 28;
        if (primera) {
            texto(subtitulo, MARGEN, y - 8, 9, false, false);
            y -= 14;
            texto("Generado: " + fechaHora(LocalDateTime.now()), MARGEN, y - 8, 9, false, false);
            y -= 14;
        } else {
            texto("(continuación)", MARGEN, y - 8, 9, false, false);
            y -= 14;
        }
        linea(MARGEN, y - 2, w - MARGEN, y - 2, 1.2f);
        y -= 14;
    }

    private void cerrarPagina() {
        if (pg == null) return;
        linea(MARGEN, MARGEN - 6, w - MARGEN, MARGEN - 6, 0.4f);
        texto("Café Café · Documento generado por el sistema", MARGEN, MARGEN - 18, 7.5f, false, false);
        texto("Página @P de @T", w - MARGEN, MARGEN - 18, 7.5f, false, true);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(pg.toString().getBytes(WIN));
        paginas.add(b);
        pg = null;
    }

    // ---------- Tabla ----------

    private void encabezadoTabla(List<Col> cols, float[] x) {
        pg.append("0.88 g ").append(f(x[0])).append(' ').append(f(y - FILA)).append(' ').append(f(x[x.length - 1] - x[0])).append(' ').append(f(FILA)).append(" re f 0 g\n");
        linea(x[0], y, x[x.length - 1], y, 0.9f);
        for (int i = 0; i < cols.size(); i++) celda(cols.get(i).nombre(), x[i], x[i + 1], y - 12, TAM_ENC, true, derecha(cols.get(i).tipo()));
        y -= FILA;
        linea(x[0], y, x[x.length - 1], y, 0.9f);
    }

    private void fila(List<Col> cols, float[] x, String[] t, boolean negrita) {
        for (int i = 0; i < cols.size(); i++) celda(t[i], x[i], x[i + 1], y - 12, TAM, negrita, derecha(cols.get(i).tipo()));
        y -= FILA;
        if (!negrita) linea(x[0], y, x[x.length - 1], y, 0.25f);
    }

    private void celda(String s, float x0, float x1, float base, float tam, boolean neg, boolean der) {
        float max = x1 - x0 - 8;
        while (s.length() > 1 && ancho(s, tam, neg) > max) s = s.substring(0, s.length() - 1);
        if (s.length() < 1) return;
        texto(s, der ? x1 - 4 : x0 + 4, base, tam, neg, der);
    }

    private static boolean derecha(Tipo t) {
        return t == Tipo.ENTERO || t == Tipo.DINERO || t == Tipo.PORCENTAJE;
    }

    private static String valor(Tipo t, Object v) {
        if (v == null) return "-";
        String s = v.toString();
        return switch (t) {
            case DINERO -> v instanceof Number n ? (n.longValue() < 0 ? "-" : "") + "L " + String.format(Locale.US, "%,.2f", Math.abs(n.longValue()) / 100.0) : s;
            case ENTERO -> v instanceof Number n ? String.format(Locale.US, "%,d", n.longValue()) : s;
            case PORCENTAJE -> v instanceof Number n ? String.format(Locale.US, "%.1f%%", n.doubleValue() * 100) : s;
            case FECHA -> s.length() >= 10 ? s.substring(8, 10) + "/" + s.substring(5, 7) + "/" + s.substring(0, 4) : s;
            case FECHAHORA -> s.length() >= 16 ? s.substring(8, 10) + "/" + s.substring(5, 7) + "/" + s.substring(0, 4) + " " + s.substring(11, 16) : s;
            default -> s;
        };
    }

    private static String fechaHora(LocalDateTime d) {
        return String.format("%02d/%02d/%d %02d:%02d", d.getDayOfMonth(), d.getMonthValue(), d.getYear(), d.getHour(), d.getMinute());
    }

    // ---------- Dibujo ----------

    private void texto(String s, float px, float py, float tam, boolean neg, boolean der) {
        if (der) px -= ancho(s, tam, neg);
        pg.append("BT /").append(neg ? "F2 " : "F1 ").append(f(tam)).append(" Tf ").append(f(px)).append(' ').append(f(py)).append(" Td (")
                .append(escapar(s)).append(") Tj ET\n");
    }

    private void linea(float x0, float y0, float x1, float y1, float grosor) {
        pg.append(f(grosor)).append(" w ").append(f(x0)).append(' ').append(f(y0)).append(" m ").append(f(x1)).append(' ').append(f(y1)).append(" l S\n");
    }

    private static float ancho(String s, float tam, boolean neg) {
        double t = 0;
        for (char c : s.toCharArray()) {
            char b = c;
            if (b > 126 || b < 32) b = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).charAt(0);
            t += (b >= 32 && b <= 126) ? ANCHO[b - 32] : 556;
        }
        return (float) (t * tam / 1000 * (neg ? 1.06 : 1));
    }

    /** Escapa ( ) \ y deja los acentos como código octal de Windows-1252 (así Helvetica los dibuja bien). */
    private static String escapar(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else if (c < 127) b.append(c);
            else {
                byte[] x = String.valueOf(c).getBytes(WIN);
                b.append((char) (x[0] & 0xFF));
            }
        }
        return b.toString();
    }

    private static String f(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    // ---------- Archivo PDF ----------

    private byte[] armar() throws IOException {
        int n = paginas.size();
        // Objetos: 1 catálogo, 2 páginas, 3 y 4 fuentes, luego por página: 5+2i página, 6+2i contenido
        List<byte[]> obj = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
        obj.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(WIN));
        obj.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>").getBytes(WIN));
        obj.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes(WIN));
        obj.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes(WIN));
        for (int i = 0; i < n; i++) {
            obj.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) w + " " + (int) h + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + (6 + 2 * i) + " 0 R >>").getBytes(WIN));
            String c = new String(paginas.get(i).toByteArray(), WIN).replace("@P", String.valueOf(i + 1)).replace("@T", String.valueOf(n));
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
                d.write(c.getBytes(WIN));
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(("<< /Length " + z.size() + " /Filter /FlateDecode >>\nstream\n").getBytes(WIN));
            o.writeBytes(z.toByteArray());
            o.writeBytes("\nendstream".getBytes(WIN));
            obj.add(o.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("%PDF-1.4\n".getBytes(WIN));
        int[] pos = new int[obj.size()];
        for (int i = 0; i < obj.size(); i++) {
            pos[i] = out.size();
            out.writeBytes(((i + 1) + " 0 obj\n").getBytes(WIN));
            out.writeBytes(obj.get(i));
            out.writeBytes("\nendobj\n".getBytes(WIN));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (obj.size() + 1) + "\n0000000000 65535 f \n");
        for (int p : pos) x.append(String.format("%010d 00000 n \n", p));
        x.append("trailer\n<< /Size ").append(obj.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF");
        out.writeBytes(x.toString().getBytes(WIN));
        return out.toByteArray();
    }
}
