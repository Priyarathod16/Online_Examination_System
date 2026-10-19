package com.onlineexam.web;

import com.onlineexam.exception.ValidationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer (no external library). Objects become LinkedHashMap, arrays ArrayList,
 * numbers BigDecimal, plus String, Boolean and null. The writer also escapes &lt; &gt; &amp; so output is safe to embed.
 */
public final class Json {
    private static final int MAX_DEPTH = 40;

    private final String s;
    private int pos;

    private Json(String s) { this.s = s; }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) throws ValidationException {
        Json p = new Json(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != p.s.length()) throw p.error("unexpected trailing characters");
        return v;
    }

    private ValidationException error(String what) {
        return new ValidationException("The request body is not valid JSON (" + what + " at position " + pos + ").");
    }

    private void skipWs() {
        while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++;
    }

    private Object value(int depth) throws ValidationException {
        if (depth > MAX_DEPTH) throw error("nesting too deep");
        if (pos >= s.length()) throw error("unexpected end");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object value) throws ValidationException {
        if (!s.startsWith(word, pos)) throw error("invalid literal");
        pos += word.length();
        return value;
    }

    private Map<String, Object> object(int depth) throws ValidationException {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++;
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') throw error("object key expected");
            String key = string();
            skipWs();
            if (peek() != ':') throw error("':' expected");
            pos++;
            skipWs();
            map.put(key, value(depth + 1));
            skipWs();
            char c = peek();
            pos++;
            if (c == '}') return map;
            if (c != ',') throw error("',' or '}' expected");
        }
    }

    private List<Object> array(int depth) throws ValidationException {
        List<Object> list = new ArrayList<Object>();
        pos++;
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value(depth + 1));
            skipWs();
            char c = peek();
            pos++;
            if (c == ']') return list;
            if (c != ',') throw error("',' or ']' expected");
        }
    }

    private char peek() throws ValidationException {
        if (pos >= s.length()) throw error("unexpected end");
        return s.charAt(pos);
    }

    private String string() throws ValidationException {
        StringBuilder sb = new StringBuilder();
        pos++;                                  // opening quote
        while (true) {
            if (pos >= s.length()) throw error("unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) throw error("bad escape");
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) throw error("bad unicode escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    pos += 4;
                    break;
                default: throw error("bad escape");
            }
        }
    }

    private BigDecimal number() throws ValidationException {
        int start = pos;
        if (s.charAt(pos) == '-') pos++;
        while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
        try {
            return new BigDecimal(s.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("invalid number");
        }
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof BigDecimal) {
            sb.append(((BigDecimal) v).toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : v.toString());
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v instanceof Enum ? ((Enum<?>) v).name() : v.toString());
        }
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': case '>': case '&':
                    sb.append(String.format("\\u%04x", (int) c));
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }
}
