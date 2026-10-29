package application.util;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/** Reglas de contraseñas y generación del código de recuperación. */
public final class Clave {

    public static final int MINIMO = 8;
    private static final Set<String> COMUNES = Set.of("12345678", "123456789", "1234567890", "password", "contrasena",
            "qwertyui", "abcd1234", "cafecafe", "admin123", "password1", "11111111", "00000000");
    // Sin 0/O/1/I para que se pueda leer y copiar a mano sin confundirse
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom AZAR = new SecureRandom();

    private Clave() {
    }

    /** Devuelve el motivo si la contraseña no sirve, o null si está bien. */
    public static String validar(String clave, String usuario) {
        if (clave == null || clave.length() < MINIMO) return "La contraseña debe tener al menos " + MINIMO + " caracteres";
        if (clave.length() > 100) return "La contraseña es demasiado larga";
        boolean letra = clave.chars().anyMatch(Character::isLetter);
        boolean numero = clave.chars().anyMatch(Character::isDigit);
        if (!letra || !numero) return "Usa letras y números en la contraseña";
        String minuscula = clave.toLowerCase(Locale.ROOT);
        if (COMUNES.contains(minuscula)) return "Esa contraseña es muy común, elige otra";
        if (usuario != null && !usuario.isBlank() && minuscula.equals(usuario.trim().toLowerCase(Locale.ROOT)))
            return "La contraseña no puede ser igual al usuario";
        return null;
    }

    /** Nombre de usuario: 3 a 40 caracteres, letras, números, punto, guion, guion bajo y arroba. Devuelve el motivo o null. */
    public static String validarUsuario(String usuario) {
        if (usuario == null || !usuario.matches("[A-Za-z0-9._@-]{3,40}"))
            return "El usuario debe tener de 3 a 40 caracteres: letras, números, punto, guion o arroba (sin espacios)";
        return null;
    }

    /** Código para recuperar la cuenta de administrador sin internet: XXXX-XXXX-XXXX-XXXX. */
    public static String codigoRecuperacion() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) b.append('-');
            b.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
        }
        return b.toString();
    }

    /** Quita guiones, espacios y mayúsculas para comparar el código tal como lo escribió la persona. */
    public static String normalizarCodigo(String codigo) {
        return codigo == null ? "" : codigo.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
}
