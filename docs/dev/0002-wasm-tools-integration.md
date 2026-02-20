# wasm-tools integration - status notes

## What was done

### wasm-tools dependency
- Added `com.dylibso.chicory:wasm-tools:1.6.1` from Maven Central
- This is the Rust wasm-tools (Bytecode Alliance) compiled to WASM, then compiled to pure Java bytecode via Chicory's compiler
- No native dependencies, runs anywhere the JVM runs
- Transitive deps: `chicory-runtime`, `chicory-wasi`, `chicory-log`, `zerofs`

### New file: `Wasm2Wat.java`
- Converts WASM binary bytes to WAT text
- Uses `WasmToolsModule` (the compiled wasm-tools) with WASI, executing `wasm-tools print -`
- Follows the same pattern as Chicory's own `Wat2Wasm` class
- Note: the 1.6.1 API for `WasiOptions.Builder` takes single args (no boolean), differs from chicory HEAD

### Modified: `Main.java`
- Detects `.wat` file extension on input
- WAT files: converted to WASM bytes via `Wat2Wasm.parse(File)` then parsed
- WASM files: read as raw bytes then parsed
- Raw bytes passed to `W9sApp` for hex viewing

### Modified: `W9sApp.java`
- Constructor now takes `byte[] wasmBytes` in addition to filename and module
- On startup: extracts per-function body bytes from raw WASM (LEB128 code section parsing), converts module to WAT via `Wasm2Wat.print()`, extracts per-function WAT blocks
- New navigation state: `inFunctionView` + `selectedFunctionIdx`
- Enter on Code section -> function detail view (three-column layout: sections | hex | WAT)
- Up/Down cycles functions, Esc/Left goes back
- `extractFunctionBodies(byte[])` - scans raw WASM for code section, extracts each function body
- `extractFunctions(String wat)` - parses WAT S-expressions to extract `(func ...)` blocks at depth 2, handles block comments `(;...;)`, line comments `;;`, and string literals
- `formatHex(byte[])` - standard hex dump format (offset + 16 bytes + ASCII)
- `readLEB128()` / `leb128Size()` - unsigned LEB128 decoding

### Native image fixes (pom.xml)
- `--initialize-at-build-time=io.roastedroot.zerofs` - zerofs FileSystemProvider SPI gets discovered by GraalVM at build time
- `-H:+ForeignAPISupport` - tamboui Panama backend uses `java.lang.foreign.Linker`
- `--enable-native-access=ALL-UNNAMED` - required alongside ForeignAPISupport

### Tests
- 10 tests total (4 original + 6 new), all passing
- New: `extractFunctionBodiesMatchesCodeSection` - body count matches code section
- New: `formatHexProducesReadableOutput` - hex format sanity check
- New: `wasm2WatProducesValidOutput` - WAT output contains `(module`
- New: `extractFunctionsFromWat` - per-function WAT count matches code section, each starts with `(func`
- New: `leb128DecodesCorrectly` - single-byte and multi-byte LEB128
- New: `wat2WasmRoundTrip` - wasm -> wat -> wasm round-trip produces valid module

## Known limitations / next steps

- No scrolling in hex/WAT panes yet - long functions will clip
- Function view only accessible from Code section; could also link from Functions/Exports sections
- WAT conversion is eager at startup - adds latency for large modules, could be made lazy
- Per-function WAT extraction relies on S-expression depth counting; robust but not a full WAT parser
- No search/filter within hex or WAT views
- `WasmToolsModule.load()` is called separately by both `Wat2Wasm` (chicory) and `Wasm2Wat` (w9s) - two copies of the module metadata in memory
- The `-H:+ForeignAPISupport` flag may have been needed before this change (tamboui-panama-backend requirement), not related to wasm-tools
