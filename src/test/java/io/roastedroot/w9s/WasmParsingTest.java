package io.roastedroot.w9s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.wasm.Parser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WasmParsingTest {

    private static final String TEST_WASM = "all-exports.wasm";
    private static List<String[]> sectionRows;
    private static byte[] wasmBytes;

    @BeforeAll
    static void setUp() throws IOException {
        var input =
                Objects.requireNonNull(
                        WasmParsingTest.class
                                .getClassLoader()
                                .getResourceAsStream(TEST_WASM));
        wasmBytes = input.readAllBytes();
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        sectionRows = W9sApp.buildSectionRows(module);
    }

    @Test
    void sectionRowsContainAllRequiredSections() {
        var names = sectionRows.stream().map(row -> row[0]).toList();
        assertTrue(names.contains("Types"));
        assertTrue(names.contains("Imports"));
        assertTrue(names.contains("Functions"));
        assertTrue(names.contains("Tables"));
        assertTrue(names.contains("Globals"));
        assertTrue(names.contains("Exports"));
        assertTrue(names.contains("Elements"));
        assertTrue(names.contains("Code"));
        assertTrue(names.contains("Data"));
    }

    @Test
    void sectionRowsHaveNameAndValue() {
        for (var row : sectionRows) {
            assertEquals(2, row.length, "Each row should have name and value");
            assertTrue(row[0] != null && !row[0].isEmpty(), "Section name must not be empty");
            assertTrue(row[1] != null && !row[1].isEmpty(), "Section value must not be empty");
        }
    }

    @Test
    void functionAndCodeCountsMatch() {
        String functionCount = null;
        String codeCount = null;
        for (var row : sectionRows) {
            if ("Functions".equals(row[0])) {
                functionCount = row[1];
            }
            if ("Code".equals(row[0])) {
                codeCount = row[1];
            }
        }
        assertEquals(functionCount, codeCount, "Function and Code section counts must match");
    }

    @Test
    void memoryRowPresentWhenModuleHasMemory() {
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        var rows = W9sApp.buildSectionRows(module);
        var hasMemoryRow = rows.stream().anyMatch(r -> "Memories".equals(r[0]));
        assertEquals(module.memorySection().isPresent(), hasMemoryRow);
    }

    @Test
    void extractFunctionBodiesMatchesCodeSection() {
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        var bodies = W9sApp.extractFunctionBodies(wasmBytes);
        assertEquals(
                module.codeSection().functionBodyCount(),
                bodies.size(),
                "Extracted function body count must match code section");
        for (var body : bodies) {
            assertTrue(body.length > 0, "Each function body must have at least one byte");
        }
    }

    @Test
    void formatHexProducesReadableOutput() {
        var data = new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        var hex = W9sApp.formatHex(data);
        assertNotNull(hex);
        assertTrue(hex.contains("00000000"), "Hex should contain offset");
        assertTrue(hex.contains("00 61 73 6d"), "Hex should contain byte values");
        assertTrue(hex.contains(".asm"), "Hex should contain ASCII representation");
    }

    @Test
    void wasm2WatProducesValidOutput() {
        var wat = Wasm2Wat.print(wasmBytes);
        assertNotNull(wat);
        assertTrue(wat.contains("(module"), "WAT should contain module declaration");
    }

    @Test
    void extractFunctionsFromWat() {
        var wat = Wasm2Wat.print(wasmBytes);
        var functions = W9sApp.extractFunctions(wat);
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        assertEquals(
                module.codeSection().functionBodyCount(),
                functions.size(),
                "Extracted WAT function count must match code section");
        for (var func : functions) {
            assertTrue(func.startsWith("(func"), "Each WAT function should start with (func");
        }
    }

    @Test
    void leb128DecodesCorrectly() {
        // Single byte: 0x05 = 5
        assertEquals(5, W9sApp.readLEB128(new byte[] {0x05}, 0));
        assertEquals(1, W9sApp.leb128Size(new byte[] {0x05}, 0));

        // Multi-byte: 0x80 0x01 = 128
        assertEquals(128, W9sApp.readLEB128(new byte[] {(byte) 0x80, 0x01}, 0));
        assertEquals(2, W9sApp.leb128Size(new byte[] {(byte) 0x80, 0x01}, 0));
    }

    @Test
    void leb128Zero() {
        assertEquals(0, W9sApp.readLEB128(new byte[] {0x00}, 0));
        assertEquals(1, W9sApp.leb128Size(new byte[] {0x00}, 0));
    }

    @Test
    void leb128MaxSingleByte() {
        // 0x7F = 127 (max single-byte LEB128)
        assertEquals(127, W9sApp.readLEB128(new byte[] {0x7F}, 0));
        assertEquals(1, W9sApp.leb128Size(new byte[] {0x7F}, 0));
    }

    @Test
    void leb128ThreeBytes() {
        // 0xE5 0x8E 0x26 = 624485
        assertEquals(
                624485,
                W9sApp.readLEB128(new byte[] {(byte) 0xE5, (byte) 0x8E, 0x26}, 0));
        assertEquals(
                3, W9sApp.leb128Size(new byte[] {(byte) 0xE5, (byte) 0x8E, 0x26}, 0));
    }

    @Test
    void leb128WithOffset() {
        // Read from a non-zero offset
        var data = new byte[] {0x00, 0x00, (byte) 0x80, 0x01};
        assertEquals(128, W9sApp.readLEB128(data, 2));
        assertEquals(2, W9sApp.leb128Size(data, 2));
    }

    @Test
    void formatHexEmptyData() {
        assertEquals("", W9sApp.formatHex(new byte[0]));
    }

    @Test
    void formatHexSingleByte() {
        var hex = W9sApp.formatHex(new byte[] {(byte) 0xAB});
        assertTrue(hex.startsWith("00000000  ab"));
        // Padding for missing bytes (15 empty slots)
        assertTrue(hex.contains("|.|\n"));
    }

    @Test
    void formatHexExactly16Bytes() {
        var data = new byte[16];
        for (int i = 0; i < 16; i++) data[i] = (byte) i;
        var hex = W9sApp.formatHex(data);
        // Should be exactly one line
        assertEquals(1, hex.split("\n").length);
        assertTrue(hex.startsWith("00000000  00 01 02 03"));
        // Gap between byte 7 and 8
        assertTrue(hex.contains("07  08"));
    }

    @Test
    void formatHexMultipleLines() {
        var data = new byte[32];
        for (int i = 0; i < 32; i++) data[i] = (byte) i;
        var hex = W9sApp.formatHex(data);
        var lines = hex.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("00000000"));
        assertTrue(lines[1].startsWith("00000010"));
    }

    @Test
    void formatHexAsciiColumn() {
        // Printable ASCII range
        var data = "Hello, World!".getBytes();
        var hex = W9sApp.formatHex(data);
        assertTrue(hex.contains("|Hello, World!|"));
    }

    @Test
    void formatHexNonPrintableAsDot() {
        var data = new byte[] {0x01, 0x7F, (byte) 0xFF};
        var hex = W9sApp.formatHex(data);
        assertTrue(hex.contains("|...|"));
    }

    @Test
    void formatHexPreviewShortData() {
        var data = new byte[] {0x0A, 0x0B, 0x0C};
        var preview = W9sApp.formatHexPreview(data, 16);
        assertEquals("0a 0b 0c", preview);
    }

    @Test
    void formatHexPreviewTruncated() {
        var data = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
        var preview = W9sApp.formatHexPreview(data, 3);
        assertEquals("01 02 03 ...", preview);
    }

    @Test
    void formatHexPreviewEmpty() {
        assertEquals("", W9sApp.formatHexPreview(new byte[0], 16));
    }

    @Test
    void formatHexPreviewExactLimit() {
        var data = new byte[] {0x01, 0x02, 0x03};
        var preview = W9sApp.formatHexPreview(data, 3);
        assertEquals("01 02 03", preview);
    }

    @Test
    void formatWatSingleLine() {
        assertEquals("   1 | hello", W9sApp.formatWat("hello"));
    }

    @Test
    void formatWatMultipleLines() {
        var result = W9sApp.formatWat("line1\nline2\nline3");
        var lines = result.split("\n");
        assertEquals(3, lines.length);
        assertEquals("   1 | line1", lines[0]);
        assertEquals("   2 | line2", lines[1]);
        assertEquals("   3 | line3", lines[2]);
    }

    @Test
    void formatWatEmptyString() {
        assertEquals("   1 | ", W9sApp.formatWat(""));
    }

    @Test
    void formatWatPreservesIndentation() {
        var result = W9sApp.formatWat("  (local i32)\n    (i32.add)");
        assertTrue(result.contains("   1 |   (local i32)"));
        assertTrue(result.contains("   2 |     (i32.add)"));
    }

    @Test
    void scrollContentNoOffset() {
        assertEquals("a\nb\nc", W9sApp.scrollContent("a\nb\nc", 0));
    }

    @Test
    void scrollContentNegativeOffset() {
        assertEquals("a\nb\nc", W9sApp.scrollContent("a\nb\nc", -1));
    }

    @Test
    void scrollContentSkipOneLines() {
        assertEquals("b\nc", W9sApp.scrollContent("a\nb\nc", 1));
    }

    @Test
    void scrollContentSkipTwoLines() {
        assertEquals("c", W9sApp.scrollContent("a\nb\nc", 2));
    }

    @Test
    void scrollContentPastEnd() {
        // Clamps to last line
        assertEquals("c", W9sApp.scrollContent("a\nb\nc", 100));
    }

    @Test
    void scrollContentSingleLine() {
        assertEquals("only", W9sApp.scrollContent("only", 0));
        assertEquals("only", W9sApp.scrollContent("only", 5));
    }

    @Test
    void extractFunctionsEmptyModule() {
        var result = W9sApp.extractFunctions("(module)");
        assertEquals(0, result.size());
    }

    @Test
    void extractFunctionsWithBlockComment() {
        var wat = "(module (; comment ;) (func $f (result i32) (i32.const 1)))";
        var result = W9sApp.extractFunctions(wat);
        assertEquals(1, result.size());
        assertTrue(result.get(0).startsWith("(func"));
    }

    @Test
    void extractFunctionsWithLineComment() {
        var wat = """
                (module
                  ;; line comment
                  (func $f (result i32) (i32.const 1))
                )""";
        var result = W9sApp.extractFunctions(wat);
        assertEquals(1, result.size());
    }

    @Test
    void extractFunctionsWithString() {
        // String containing parens should not confuse the parser
        var wat = """
                (module
                  (func (export "hello()") (result i32) (i32.const 42))
                )""";
        var result = W9sApp.extractFunctions(wat);
        assertEquals(1, result.size());
    }

    @Test
    void extractFunctionsMultiple() {
        var wat = """
                (module
                  (func $a (result i32) (i32.const 1))
                  (func $b (param i32) (drop (local.get 0)))
                )""";
        var result = W9sApp.extractFunctions(wat);
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("$a"));
        assertTrue(result.get(1).contains("$b"));
    }

    @Test
    void extractFunctionsNestedExpressions() {
        var wat = """
                (module
                  (func $nested (result i32)
                    (i32.add
                      (i32.mul (i32.const 2) (i32.const 3))
                      (i32.const 4)))
                )""";
        var result = W9sApp.extractFunctions(wat);
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("i32.add"));
    }

    @Test
    void extractFunctionBodiesEmptyWasm() {
        // Not a valid WASM but should not throw
        var result = W9sApp.extractFunctionBodies(new byte[0]);
        assertEquals(0, result.size());
    }

    @Test
    void extractFunctionBodiesInvalidMagic() {
        // Wrong magic number, should return empty
        var result = W9sApp.extractFunctionBodies(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
        assertEquals(0, result.size());
    }

    @Test
    void wat2WasmRoundTrip() {
        // Convert WASM to WAT and back
        var wat = Wasm2Wat.print(wasmBytes);
        assertNotNull(wat);
        assertFalse(wat.isEmpty());

        var roundTripped = com.dylibso.chicory.tools.wasm.Wat2Wasm.parse(wat);
        assertNotNull(roundTripped);
        assertTrue(roundTripped.length > 0, "Round-tripped WASM should not be empty");

        // Verify the round-tripped module is valid
        var module = Parser.parse(new ByteArrayInputStream(roundTripped));
        assertNotNull(module);
    }
}
