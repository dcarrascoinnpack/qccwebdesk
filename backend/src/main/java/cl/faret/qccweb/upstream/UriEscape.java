package cl.faret.qccweb.upstream;

import java.nio.charset.StandardCharsets;

/**
 * Equivalente de System.Uri.EscapeDataString (.NET) para armar las mismas query strings que
 * Photino: percent-encoding RFC 3986 de todo salvo los no reservados (A-Z a-z 0-9 - _ . ~),
 * espacio como %20 (no "+"), UTF-8 para caracteres no ASCII.
 */
public final class UriEscape {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UriEscape() {}

    public static String dataString(String valor) {
        if (valor == null || valor.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(valor.length() * 2);
        for (byte b : valor.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }
}
