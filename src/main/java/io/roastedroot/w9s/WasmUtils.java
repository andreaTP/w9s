package io.roastedroot.w9s;

import com.dylibso.chicory.wasm.WasmModule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WasmUtils {

    private WasmUtils() {}

    static List<String[]> buildSectionRows(WasmModule module) {
        var rows = new ArrayList<String[]>();
        rows.add(new String[] {"Types", String.valueOf(module.typeSection().typeCount())});
        rows.add(
                new String[] {
                    "Imports", String.valueOf(module.importSection().importCount())
                });
        rows.add(
                new String[] {
                    "Functions", String.valueOf(module.functionSection().functionCount())
                });
        rows.add(
                new String[] {
                    "Tables", String.valueOf(module.tableSection().tableCount())
                });
        module.memorySection()
                .ifPresent(
                        mem ->
                                rows.add(
                                        new String[] {
                                            "Memories", String.valueOf(mem.memoryCount())
                                        }));
        rows.add(
                new String[] {
                    "Globals", String.valueOf(module.globalSection().globalCount())
                });
        rows.add(
                new String[] {
                    "Exports", String.valueOf(module.exportSection().exportCount())
                });
        module.startSection()
                .ifPresent(
                        start ->
                                rows.add(
                                        new String[] {
                                            "Start", "function #" + start.startIndex()
                                        }));
        rows.add(
                new String[] {
                    "Elements", String.valueOf(module.elementSection().elementCount())
                });
        rows.add(
                new String[] {
                    "Code", String.valueOf(module.codeSection().functionBodyCount())
                });
        rows.add(
                new String[] {
                    "Data", String.valueOf(module.dataSection().dataSegmentCount())
                });
        return rows;
    }

    static String formatHex(byte[] data) {
        var sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%08x  ", i));
            int lineLen = Math.min(16, data.length - i);
            for (int j = 0; j < 16; j++) {
                if (j < lineLen) {
                    sb.append(String.format("%02x ", data[i + j] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (j == 7) {
                    sb.append(" ");
                }
            }
            sb.append(" |");
            for (int j = 0; j < lineLen; j++) {
                int b = data[i + j] & 0xFF;
                sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }

    static List<byte[]> extractFunctionBodies(byte[] wasmBytes) {
        try {
            int pos = 8; // skip magic (4 bytes) + version (4 bytes)
            while (pos < wasmBytes.length) {
                int sectionId = wasmBytes[pos++] & 0xFF;
                int sectionSize = readLEB128(wasmBytes, pos);
                int sizeLen = leb128Size(wasmBytes, pos);
                pos += sizeLen;

                if (sectionId == 10) { // code section
                    var bodies = new ArrayList<byte[]>();
                    int count = readLEB128(wasmBytes, pos);
                    pos += leb128Size(wasmBytes, pos);

                    for (int i = 0; i < count; i++) {
                        int bodySize = readLEB128(wasmBytes, pos);
                        int bodySizeLen = leb128Size(wasmBytes, pos);
                        pos += bodySizeLen;
                        bodies.add(Arrays.copyOfRange(wasmBytes, pos, pos + bodySize));
                        pos += bodySize;
                    }
                    return bodies;
                } else {
                    pos += sectionSize;
                }
            }
        } catch (Exception e) {
            // Fall through to return empty list
        }
        return List.of();
    }

    static int readLEB128(byte[] data, int offset) {
        int result = 0;
        int shift = 0;
        int pos = offset;
        while (true) {
            int b = data[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    static int leb128Size(byte[] data, int offset) {
        int pos = offset;
        while ((data[pos++] & 0x80) != 0) {
            // advance past continuation bytes
        }
        return pos - offset;
    }

    static List<String> extractFunctions(String wat) {
        var functions = new ArrayList<String>();
        int depth = 0;
        int funcStart = -1;
        int i = 0;

        while (i < wat.length()) {
            char c = wat.charAt(i);

            // Handle block comments (; ... ;)
            if (c == '(' && i + 1 < wat.length() && wat.charAt(i + 1) == ';') {
                int commentDepth = 1;
                i += 2;
                while (i < wat.length() && commentDepth > 0) {
                    if (wat.charAt(i) == '('
                            && i + 1 < wat.length()
                            && wat.charAt(i + 1) == ';') {
                        commentDepth++;
                        i += 2;
                    } else if (wat.charAt(i) == ';'
                            && i + 1 < wat.length()
                            && wat.charAt(i + 1) == ')') {
                        commentDepth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                continue;
            }

            // Handle line comments
            if (c == ';' && i + 1 < wat.length() && wat.charAt(i + 1) == ';') {
                while (i < wat.length() && wat.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // Handle strings
            if (c == '"') {
                i++;
                while (i < wat.length() && wat.charAt(i) != '"') {
                    if (wat.charAt(i) == '\\') {
                        i++; // skip escaped char
                    }
                    i++;
                }
                i++; // skip closing "
                continue;
            }

            if (c == '(') {
                depth++;
                if (depth == 2
                        && wat.startsWith("(func", i)
                        && (i + 5 >= wat.length()
                                || !Character.isLetterOrDigit(wat.charAt(i + 5)))) {
                    funcStart = i;
                }
            } else if (c == ')') {
                if (depth == 2 && funcStart >= 0) {
                    functions.add(wat.substring(funcStart, i + 1));
                    funcStart = -1;
                }
                depth--;
            }

            i++;
        }
        return functions;
    }

    static String formatWat(String wat) {
        var lines = wat.split("\n");
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.format("%4d | %s", i + 1, lines[i]));
        }
        return sb.toString();
    }

    static String scrollContent(String content, int offset) {
        if (offset <= 0) return content;
        var lines = content.split("\n", -1);
        int start = Math.min(offset, lines.length - 1);
        var sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String scrollAnsiContent(String content, int offset) {
        if (offset <= 0) return content;
        var lines = content.split("\n", -1);
        int start = Math.min(offset, lines.length - 1);
        var sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String addLineNumbers(String ansiContent) {
        var lines = ansiContent.split("\n", -1);
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.format("%4d | %s", i + 1, lines[i]));
        }
        return sb.toString();
    }
}
