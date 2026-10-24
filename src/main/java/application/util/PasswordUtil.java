package application.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Contraseñas cifradas con PBKDF2 (viene con Java, no necesita librerías ni internet). */
public final class PasswordUtil {

    private static final int ITERACIONES = 600_000;
    private static final int SAL_BYTES = 16;
    private static final int CLAVE_BITS = 256;

    private PasswordUtil() {
    }

    /** Formato guardado: pbkdf2$iteraciones$sal$hash */
    public static String hash(String clave) {
        byte[] sal = new byte[SAL_BYTES];
        new SecureRandom().nextBytes(sal);
        byte[] h = derivar(clave, sal, ITERACIONES);
        return "pbkdf2$" + ITERACIONES + "$" + b64(sal) + "$" + b64(h);
    }

    public static boolean verificar(String clave, String guardado) {
        if (clave == null || guardado == null) return false;
        String[] p = guardado.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        try {
            byte[] esperado = Base64.getDecoder().decode(p[3]);
            byte[] calculado = derivar(clave, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(esperado, calculado);   // comparación en tiempo constante
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derivar(String clave, byte[] sal, int iteraciones) {
        try {
            PBEKeySpec spec = new PBEKeySpec(clave.toCharArray(), sal, iteraciones, CLAVE_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar la contraseña", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
