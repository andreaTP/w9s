package io.roastedroot.w9s;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ValType;
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
        sectionRows = WasmUtils.buildSectionRows(module);
    }

    // --- Section rows ---

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
    void functionAndCodeCountsMatch() {
        String functionCount = null;
        String codeCount = null;
        for (var row : sectionRows) {
            if ("Functions".equals(row[0])) functionCount = row[1];
            if ("Code".equals(row[0])) codeCount = row[1];
        }
        assertEquals(functionCount, codeCount, "Function and Code section counts must match");
    }

    @Test
    void memoryRowPresentWhenModuleHasMemory() {
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        var rows = WasmUtils.buildSectionRows(module);
        var hasMemoryRow = rows.stream().anyMatch(r -> "Memories".equals(r[0]));
        assertEquals(module.memorySection().isPresent(), hasMemoryRow);
    }

    // --- Function body extraction ---

    @Test
    void extractFunctionBodiesMatchesCodeSection() {
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        var bodies = WasmUtils.extractFunctionBodies(wasmBytes);
        assertEquals(
                module.codeSection().functionBodyCount(),
                bodies.size(),
                "Extracted function body count must match code section");
        for (var body : bodies) {
            assertTrue(body.length > 0, "Each function body must have at least one byte");
        }
    }

    @Test
    void extractFunctionBodiesEmptyWasm() {
        assertEquals(0, WasmUtils.extractFunctionBodies(new byte[0]).size());
    }

    @Test
    void extractFunctionBodiesInvalidMagic() {
        assertEquals(0, WasmUtils.extractFunctionBodies(new byte[] {0, 0, 0, 0, 0, 0, 0, 0}).size());
    }

    // --- Hex formatting ---

    @Test
    void formatHexProducesReadableOutput() {
        var data = new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        var hex = WasmUtils.formatHex(data);
        assertNotNull(hex);
        assertTrue(hex.contains("00000000"), "Hex should contain offset");
        assertTrue(hex.contains("00 61 73 6d"), "Hex should contain byte values");
        assertTrue(hex.contains(".asm"), "Hex should contain ASCII representation");
    }

    @Test
    void formatHexEmptyData() {
        assertEquals("", WasmUtils.formatHex(new byte[0]));
    }

    @Test
    void formatHexSingleByte() {
        var hex = WasmUtils.formatHex(new byte[] {(byte) 0xAB});
        assertTrue(hex.startsWith("00000000  ab"));
        assertTrue(hex.contains("|.|\n"));
    }

    @Test
    void formatHexExactly16Bytes() {
        var data = new byte[16];
        for (int i = 0; i < 16; i++) data[i] = (byte) i;
        var hex = WasmUtils.formatHex(data);
        assertEquals(1, hex.split("\n").length);
        assertTrue(hex.startsWith("00000000  00 01 02 03"));
        assertTrue(hex.contains("07  08"));
    }

    @Test
    void formatHexMultipleLines() {
        var data = new byte[32];
        for (int i = 0; i < 32; i++) data[i] = (byte) i;
        var hex = WasmUtils.formatHex(data);
        var lines = hex.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("00000000"));
        assertTrue(lines[1].startsWith("00000010"));
    }

    // --- WAT formatting ---

    @Test
    void wasm2WatProducesValidOutput() {
        var wat = Wasm2Wat.print(wasmBytes);
        assertNotNull(wat);
        assertTrue(wat.contains("(module"), "WAT should contain module declaration");
    }

    @Test
    void formatWatSingleLine() {
        assertEquals("   1 | hello", WasmUtils.formatWat("hello"));
    }

    @Test
    void formatWatMultipleLines() {
        var result = WasmUtils.formatWat("line1\nline2\nline3");
        var lines = result.split("\n");
        assertEquals(3, lines.length);
        assertEquals("   1 | line1", lines[0]);
        assertEquals("   2 | line2", lines[1]);
        assertEquals("   3 | line3", lines[2]);
    }

    @Test
    void formatWatPreservesIndentation() {
        var result = WasmUtils.formatWat("  (local i32)\n    (i32.add)");
        assertTrue(result.contains("   1 |   (local i32)"));
        assertTrue(result.contains("   2 |     (i32.add)"));
    }

    // --- WAT function extraction ---

    @Test
    void extractFunctionsFromWat() {
        var wat = Wasm2Wat.print(wasmBytes);
        var functions = WasmUtils.extractFunctions(wat);
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
    void extractFunctionsEmptyModule() {
        assertEquals(0, WasmUtils.extractFunctions("(module)").size());
    }

    @Test
    void extractFunctionsWithBlockComment() {
        var wat = "(module (; comment ;) (func $f (result i32) (i32.const 1)))";
        var result = WasmUtils.extractFunctions(wat);
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
        assertEquals(1, WasmUtils.extractFunctions(wat).size());
    }

    @Test
    void extractFunctionsWithString() {
        var wat = """
                (module
                  (func (export "hello()") (result i32) (i32.const 42))
                )""";
        assertEquals(1, WasmUtils.extractFunctions(wat).size());
    }

    @Test
    void extractFunctionsMultiple() {
        var wat = """
                (module
                  (func $a (result i32) (i32.const 1))
                  (func $b (param i32) (drop (local.get 0)))
                )""";
        var result = WasmUtils.extractFunctions(wat);
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
        var result = WasmUtils.extractFunctions(wat);
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("i32.add"));
    }

    // --- LEB128 ---

    @Test
    void leb128DecodesCorrectly() {
        // Zero
        assertEquals(0, WasmUtils.readLEB128(new byte[] {0x00}, 0));
        assertEquals(1, WasmUtils.leb128Size(new byte[] {0x00}, 0));

        // Single byte: 0x05 = 5
        assertEquals(5, WasmUtils.readLEB128(new byte[] {0x05}, 0));
        assertEquals(1, WasmUtils.leb128Size(new byte[] {0x05}, 0));

        // Max single byte: 0x7F = 127
        assertEquals(127, WasmUtils.readLEB128(new byte[] {0x7F}, 0));
        assertEquals(1, WasmUtils.leb128Size(new byte[] {0x7F}, 0));

        // Two bytes: 0x80 0x01 = 128
        assertEquals(128, WasmUtils.readLEB128(new byte[] {(byte) 0x80, 0x01}, 0));
        assertEquals(2, WasmUtils.leb128Size(new byte[] {(byte) 0x80, 0x01}, 0));
    }

    @Test
    void leb128ThreeBytes() {
        assertEquals(624485, WasmUtils.readLEB128(new byte[] {(byte) 0xE5, (byte) 0x8E, 0x26}, 0));
        assertEquals(3, WasmUtils.leb128Size(new byte[] {(byte) 0xE5, (byte) 0x8E, 0x26}, 0));
    }

    @Test
    void leb128WithOffset() {
        var data = new byte[] {0x00, 0x00, (byte) 0x80, 0x01};
        assertEquals(128, WasmUtils.readLEB128(data, 2));
        assertEquals(2, WasmUtils.leb128Size(data, 2));
    }

    // --- Scroll ---

    @Test
    void scrollContentNoOffset() {
        assertEquals("a\nb\nc", WasmUtils.scrollContent("a\nb\nc", 0));
    }

    @Test
    void scrollContentSkipsLines() {
        assertEquals("b\nc", WasmUtils.scrollContent("a\nb\nc", 1));
        assertEquals("c", WasmUtils.scrollContent("a\nb\nc", 2));
    }

    @Test
    void scrollContentClampsPastEnd() {
        assertEquals("c", WasmUtils.scrollContent("a\nb\nc", 100));
    }

    @Test
    void scrollContentSingleLine() {
        assertEquals("only", WasmUtils.scrollContent("only", 0));
        assertEquals("only", WasmUtils.scrollContent("only", 5));
    }

    // --- Rust demangling ---

    @Test
    void demangleRustSymbol() {
        try (var demangler = new RustcDemangle()) {
            assertEquals("hello::world", demangler.demangle("_ZN5hello5world17h0123456789abcdefE"));
            assertEquals("hello::world", demangler.demangle("_RNvCs1234_5hello5world"));
        }
    }

    @Test
    void demangleNonRustSymbol() {
        try (var demangler = new RustcDemangle()) {
            assertEquals("plain_name", demangler.demangle("plain_name"));
            assertEquals("_start", demangler.demangle("_start"));
        }
    }

    @Test
    void demangleEmptyAndNull() {
        try (var demangler = new RustcDemangle()) {
            assertEquals("", demangler.demangle(""));
            assertEquals(null, demangler.demangle(null));
        }
    }

    // --- ParamUtils.parseParam ---

    @Test
    void parseParamI32() {
        assertEquals(42L, ParamUtils.parseParam(ValType.I32, "42"));
        assertEquals(0xFFFFFFFFL, ParamUtils.parseParam(ValType.I32, "-1"));
        assertEquals(0xFFL, ParamUtils.parseParam(ValType.I32, "0xFF"));
        assertEquals(0xABCDL, ParamUtils.parseParam(ValType.I32, "0XABCD"));
    }

    @Test
    void parseParamI64() {
        assertEquals(42L, ParamUtils.parseParam(ValType.I64, "42"));
        assertEquals(-1L, ParamUtils.parseParam(ValType.I64, "-1"));
        assertEquals(0xFFL, ParamUtils.parseParam(ValType.I64, "0xFF"));
    }

    @Test
    void parseParamF32() {
        long raw = ParamUtils.parseParam(ValType.F32, "3.14");
        float f = Float.intBitsToFloat((int) raw);
        assertEquals(3.14f, f, 0.001f);
    }

    @Test
    void parseParamF64() {
        long raw = ParamUtils.parseParam(ValType.F64, "3.14");
        double d = Double.longBitsToDouble(raw);
        assertEquals(3.14, d, 0.001);
    }

    @Test
    void parseParamUnsupportedTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParamUtils.parseParam(ValType.FuncRef, "0"));
    }

    // --- ParamUtils.formatReturnValue ---

    @Test
    void formatReturnValueI32() {
        assertEquals("42 (0x2a)", ParamUtils.formatReturnValue(ValType.I32, 42L));
    }

    @Test
    void formatReturnValueI64() {
        assertEquals("256 (0x100)", ParamUtils.formatReturnValue(ValType.I64, 256L));
    }

    @Test
    void formatReturnValueF32() {
        long raw = Float.floatToRawIntBits(1.5f) & 0xFFFFFFFFL;
        assertEquals("1.5", ParamUtils.formatReturnValue(ValType.F32, raw));
    }

    @Test
    void formatReturnValueF64() {
        long raw = Double.doubleToRawLongBits(2.5);
        assertEquals("2.5", ParamUtils.formatReturnValue(ValType.F64, raw));
    }

    // --- ParamUtils.encodeTypedValue ---

    @Test
    void encodeTypedValueI32() {
        assertArrayEquals(new byte[] {1, 0, 0, 0}, ParamUtils.encodeTypedValue("i32", "1"));
        assertArrayEquals(new byte[] {0, 1, 0, 0}, ParamUtils.encodeTypedValue("i32", "256"));
    }

    @Test
    void encodeTypedValueI64() {
        var bytes = ParamUtils.encodeTypedValue("i64", "1");
        assertEquals(8, bytes.length);
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, 0, 0}, bytes);
    }

    @Test
    void encodeTypedValueF32() {
        var bytes = ParamUtils.encodeTypedValue("f32", "1.0");
        assertEquals(4, bytes.length);
        int bits = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
                | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 24);
        assertEquals(1.0f, Float.intBitsToFloat(bits));
    }

    @Test
    void encodeTypedValueF64() {
        var bytes = ParamUtils.encodeTypedValue("f64", "1.0");
        assertEquals(8, bytes.length);
        long bits = 0;
        for (int i = 0; i < 8; i++) bits |= ((long) (bytes[i] & 0xFF)) << (i * 8);
        assertEquals(1.0, Double.longBitsToDouble(bits));
    }

    @Test
    void encodeTypedValueUnknownTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParamUtils.encodeTypedValue("v128", "0"));
    }
}
