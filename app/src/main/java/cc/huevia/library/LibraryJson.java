package cc.huevia.library;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec shared by the bounded catalog and private library metadata. */
final class LibraryJson {
    private LibraryJson() {}

    static Object parse(String text) throws IOException {
        Parser p = new Parser(text);
        Object value = p.value(0);
        p.space();
        if (p.at != text.length()) throw p.error("JSON 末尾存在额外内容");
        return value;
    }

    static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException("需要 JSON 对象。");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    static String string(Map<String, Object> map, String key, boolean required, int max) throws IOException {
        Object value = map.get(key);
        if (value == null && !required) return "";
        if (!(value instanceof String)) throw new IOException("字段 " + key + " 必须是字符串。");
        String text = (String) value;
        if (text.length() > max || (required && text.trim().isEmpty()))
            throw new IOException("字段 " + key + " 为空或过长。");
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 0x20)
            throw new IOException("字段 " + key + " 包含控制字符。");
        return text;
    }

    static long integer(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof BigDecimal)) throw new IOException("字段 " + key + " 必须是整数。");
        try { return ((BigDecimal) value).longValueExact(); }
        catch (ArithmeticException e) { throw new IOException("字段 " + key + " 不是有效整数。", e); }
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean || value instanceof Number) out.append(value);
        else if (value instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(','); first = false;
                quote(out, (String) e.getKey()); out.append(':'); append(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('['); boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(','); first = false; append(out, item);
            }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    private static final class Parser {
        final String text; int at;
        Parser(String text) { this.text = text; }
        IOException error(String reason) { return new IOException(reason + "（位置 " + at + "）。"); }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        Object value(int depth) throws IOException {
            if (depth > 24) throw error("JSON 嵌套过深");
            space(); if (at == text.length()) throw error("JSON 意外结束");
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++; space(); Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    space(); if (at == text.length() || text.charAt(at) != '"') throw error("JSON 键必须是字符串");
                    String key = string(); space(); require(':');
                    if (map.containsKey(key)) throw error("JSON 包含重复键");
                    map.put(key, value(depth + 1)); space();
                    if (map.size() > 4096) throw error("JSON 对象过大");
                    if (take('}')) return map;
                    require(',');
                } while (true);
            }
            if (c == '[') {
                at++; space(); List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do {
                    list.add(value(depth + 1)); space();
                    if (list.size() > 4096) throw error("JSON 数组过大");
                    if (take(']')) return list;
                    require(',');
                } while (true);
            }
            if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
            if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
            if (text.startsWith("null", at)) { at += 4; return null; }
            return number();
        }
        boolean take(char c) {
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void require(char c) throws IOException { if (!take(c)) throw error("JSON 缺少 '" + c + "'"); }
        String string() throws IOException {
            require('"'); StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    String result = out.toString();
                    for (int i = 0; i < result.length(); i++) {
                        char u = result.charAt(i);
                        if (Character.isHighSurrogate(u)) {
                            if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i)))
                                throw error("JSON 含无效 Unicode");
                        } else if (Character.isLowSurrogate(u)) throw error("JSON 含无效 Unicode");
                    }
                    return result;
                }
                if (c < 0x20) throw error("JSON 字符串包含未转义控制字符");
                if (c == '\\') {
                    if (at == text.length()) throw error("JSON 转义被截断");
                    char e = text.charAt(at++);
                    switch (e) {
                        case '"': case '\\': case '/': c = e; break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            if (text.length() - at < 4) throw error("JSON Unicode 转义被截断");
                            int n = 0;
                            for (int j = 0; j < 4; j++) {
                                int digit = Character.digit(text.charAt(at++), 16);
                                if (digit < 0) throw error("JSON Unicode 转义无效");
                                n = n * 16 + digit;
                            }
                            c = (char) n; break;
                        default: throw error("JSON 转义无效");
                    }
                }
                out.append(c);
            }
            throw error("JSON 字符串未结束");
        }
        BigDecimal number() throws IOException {
            int start = at; take('-');
            if (take('0')) { if (at < text.length() && Character.isDigit(text.charAt(at))) throw error("JSON 数字前导零"); }
            else { if (at == text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') throw error("JSON 值无效"); digits(); }
            if (take('.')) { int before = at; digits(); if (before == at) throw error("JSON 小数无效"); }
            if (take('e') || take('E')) {
                if (!take('+')) take('-'); int before = at; digits();
                if (before == at) throw error("JSON 指数无效");
            }
            if (at - start > 64) throw error("JSON 数字过长");
            try { return new BigDecimal(text.substring(start, at)); }
            catch (NumberFormatException e) { throw error("JSON 数字无效"); }
        }
        void digits() { while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++; }
    }
}
