package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.TableLimits;
import com.dylibso.chicory.wasm.types.ValType;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;
import io.roastedroot.lumis4j.core.Formatter;
import io.roastedroot.lumis4j.core.Highlighter;
import io.roastedroot.lumis4j.core.Lang;
import io.roastedroot.lumis4j.core.Lumis;
import io.roastedroot.lumis4j.core.Theme;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class W9sApp {

    private final String filename;
    private final WasmModule module;
    private final TableState tableState = new TableState();
    private final List<String[]> sectionRows;

    // Dual view data
    private final List<byte[]> functionBodies;
    private final CompletableFuture<List<String>> functionWatsFuture;
    private final CompletableFuture<List<String>> functionNamesFuture;
    private final java.util.Map<Integer, String> highlightedWatCache = new java.util.HashMap<>();

    // Navigation state for detail pane
    private final TableState detailTableState = new TableState();
    private boolean inDetailView = false;

    // Search/filter state
    private boolean inSearch = false;
    private String searchFilter = "";

    // Navigation state for function detail view
    private boolean inFunctionView = false;
    private int selectedFunctionIdx = 0;
    private boolean showWatMode = true; // false = hex, true = WAT
    private int functionViewScrollOffset = 0;

    // Navigation state for data hex dump view
    private boolean inDataView = false;
    private int selectedDataIdx = 0;
    private int dataViewScrollOffset = 0;

    // Content view search state (shared by function view and data view)
    private boolean inContentSearch = false;
    private String contentSearchQuery = "";
    private int contentSearchMatchLine = -1;

    // === Instance lifecycle (shared between run and memory editor) ===
    private Instance wasmInstance;
    private WasiPreview1 wasi;
    private String instanceError;
    private boolean instanceNeedsReset;

    // === Run function state ===
    private boolean inRunParamView = false;
    private boolean inRunOutputView = false;
    private String runExportName;
    private List<ValType> runParamTypes;
    private List<ValType> runReturnTypes;
    private String[] runParamValues;
    private int runParamFocusIdx = 0;
    private String runParamError;
    private long[] runResults;
    private String runStdout = "";
    private String runStderr = "";
    private String runExecError;
    private int runExecExitCode = -1;
    private long runExecDurationMs;
    private int runOutputScrollOffset = 0;

    // WASI I/O capture
    private ByteArrayOutputStream wasiStdoutCapture;
    private ByteArrayOutputStream wasiStderrCapture;

    // === Memory editor state ===
    private boolean inMemoryView = false;
    private int memViewAddress = 0;
    private boolean inMemGoto = false;
    private String memGotoInput = "";
    private boolean inMemWriteString = false;
    private boolean memWriteAddrPhase = true;
    private String memWriteAddr = "";
    private String memWriteStringInput = "";
    private boolean memWriteNullTerm = false;
    private boolean inMemWriteTyped = false;
    private int memWriteTypedPhase = 0; // 0=addr, 1=type, 2=value
    private String memWriteTypedAddr = "";
    private int memWriteTypedTypeIdx = 0;
    private String memWriteTypedValue = "";
    private static final String[] TYPED_VALUE_TYPES = {"i32", "i64", "f32", "f64"};
    private String memStatusMessage;

    // === Global editor state ===
    private boolean inGlobalEdit = false;
    private int globalEditIdx = -1;
    private String globalEditValue = "";
    private String globalEditError;

    private volatile Lumis lumis;
    private volatile Highlighter watHighlighter;
    private final CompletableFuture<Void> highlighterReady;

    private static final int PAGE_SIZE = 20;

    public W9sApp(String filename, WasmModule module, byte[] wasmBytes) {
        this.filename = filename;
        this.module = module;
        this.sectionRows = buildSectionRows(module);
        tableState.select(0);

        this.functionBodies = extractFunctionBodies(wasmBytes);
        this.functionNamesFuture = CompletableFuture.supplyAsync(() -> buildFunctionNames(module));

        this.functionWatsFuture =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return extractFunctions(Wasm2Wat.print(wasmBytes));
                            } catch (Exception e) {
                                return List.of();
                            }
                        });

        this.highlighterReady =
                CompletableFuture.runAsync(
                        () -> {
                            this.lumis = Lumis.builder().build();
                            this.watHighlighter =
                                    lumis.highlighter()
                                            .withLang(Lang.WAT)
                                            .withTheme(Theme.DRACULA)
                                            .withFormatter(Formatter.TERMINAL)
                                            .build();
                        });
    }

    public void run() throws Exception {
        var config = TuiConfig.builder().tickRate(Duration.ofMillis(100)).build();
        try (var runner = ToolkitRunner.create(config)) {
            runner.eventRouter()
                    .addGlobalHandler(
                            event -> {
                                if (event instanceof KeyEvent key) {
                                    return handleKey(key, runner);
                                }
                                return EventResult.UNHANDLED;
                            });
            runner.run(this::render);
        } finally {
            if (wasi != null) {
                wasi.close();
            }
            highlighterReady.join();
            if (lumis != null) {
                lumis.close();
            }
        }
    }

    // === Instance management ===

    private boolean ensureInstance() {
        if (instanceNeedsReset) {
            if (wasi != null) {
                wasi.close();
                wasi = null;
            }
            wasmInstance = null;
            instanceError = null;
            instanceNeedsReset = false;
        }
        if (instanceError != null) {
            return false;
        }
        if (wasmInstance != null) {
            return true;
        }

        // Check for unresolvable imports
        var unresolvable = findUnresolvableImports();
        if (!unresolvable.isEmpty()) {
            var sb = new StringBuilder("Module has unresolvable imports:\n");
            for (var imp : unresolvable) {
                sb.append("  ").append(imp).append('\n');
            }
            instanceError = sb.toString();
            return false;
        }

        try {
            var builder = ImportValues.builder();
            if (moduleNeedsWasi()) {
                wasiStdoutCapture = new ByteArrayOutputStream();
                wasiStderrCapture = new ByteArrayOutputStream();
                var wasiOpts =
                        WasiOptions.builder()
                                .withStdout(wasiStdoutCapture)
                                .withStderr(wasiStderrCapture)
                                .build();
                wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
                builder.addFunction(wasi.toHostFunctions());
            }
            var imports = builder.build();
            wasmInstance =
                    Instance.builder(module)
                            .withImportValues(imports)
                            .withStart(false)
                            .build();
            return true;
        } catch (Exception e) {
            instanceError = "Failed to instantiate module: " + e.getMessage();
            return false;
        }
    }

    private boolean moduleNeedsWasi() {
        var is = module.importSection();
        for (int i = 0; i < is.importCount(); i++) {
            var imp = is.getImport(i);
            if ("wasi_snapshot_preview1".equals(imp.module())) {
                return true;
            }
        }
        return false;
    }

    private List<String> findUnresolvableImports() {
        var unresolvable = new ArrayList<String>();
        var is = module.importSection();
        for (int i = 0; i < is.importCount(); i++) {
            var imp = is.getImport(i);
            if (!"wasi_snapshot_preview1".equals(imp.module())) {
                unresolvable.add(imp.module() + "." + imp.name()
                        + " (" + imp.importType().name().toLowerCase() + ")");
            }
        }
        return unresolvable;
    }

    // === Parameter parsing and formatting ===

    private long parseParam(ValType type, String value) {
        var v = value.trim();
        if (ValType.I32.equals(type)) {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Integer.parseUnsignedInt(v.substring(2), 16) & 0xFFFFFFFFL;
            }
            return Integer.parseInt(v) & 0xFFFFFFFFL;
        } else if (ValType.I64.equals(type)) {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Long.parseUnsignedLong(v.substring(2), 16);
            }
            return Long.parseLong(v);
        } else if (ValType.F32.equals(type)) {
            return Float.floatToRawIntBits(Float.parseFloat(v)) & 0xFFFFFFFFL;
        } else if (ValType.F64.equals(type)) {
            return Double.doubleToRawLongBits(Double.parseDouble(v));
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private String formatReturnValue(ValType type, long value) {
        if (ValType.I32.equals(type)) {
            int i = (int) value;
            return i + " (0x" + Integer.toHexString(i) + ")";
        } else if (ValType.I64.equals(type)) {
            return value + " (0x" + Long.toHexString(value) + ")";
        } else if (ValType.F32.equals(type)) {
            float f = Float.intBitsToFloat((int) value);
            return String.valueOf(f);
        } else if (ValType.F64.equals(type)) {
            double d = Double.longBitsToDouble(value);
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    // === Run function ===

    private void handleRunExport() {
        var detailIdx =
                detailTableState.selected() != null ? detailTableState.selected() : 0;
        var es = module.exportSection();
        if (detailIdx >= es.exportCount()) return;
        var exp = es.getExport(detailIdx);
        if (exp.exportType() != ExternalType.FUNCTION) return;

        runExportName = exp.name();
        int importedFuncs = module.importSection().count(ExternalType.FUNCTION);
        int localIdx = (int) exp.index() - importedFuncs;

        // Get function type
        int typeIdx;
        if (localIdx >= 0 && localIdx < module.functionSection().functionCount()) {
            typeIdx = module.functionSection().getFunctionType(localIdx);
        } else {
            // It's an imported function
            var imp = module.importSection().getImport((int) exp.index());
            if (imp instanceof FunctionImport funcImport) {
                typeIdx = funcImport.typeIndex();
            } else {
                return;
            }
        }
        var ft = module.typeSection().getType(typeIdx);
        runParamTypes = ft.params();
        runReturnTypes = ft.returns();

        // Check for reference types
        for (var pt : runParamTypes) {
            if (pt.isReference()) {
                runExecError = "Reference type parameters are not supported";
                inRunOutputView = true;
                inDetailView = false;
                return;
            }
        }

        if (!ensureInstance()) {
            runExecError = instanceError;
            runResults = null;
            runStdout = "";
            runStderr = "";
            runExecExitCode = -1;
            inRunOutputView = true;
            inDetailView = false;
            return;
        }

        if (runParamTypes.isEmpty()) {
            executeFunction(new long[0]);
        } else {
            runParamValues = new String[runParamTypes.size()];
            Arrays.fill(runParamValues, "");
            runParamFocusIdx = 0;
            runParamError = null;
            inRunParamView = true;
            inDetailView = false;
        }
    }

    private void executeFunction(long[] args) {
        runExecError = null;
        runResults = null;
        runExecExitCode = -1;
        runStdout = "";
        runStderr = "";

        if (wasiStdoutCapture != null) {
            wasiStdoutCapture.reset();
        }
        if (wasiStderrCapture != null) {
            wasiStderrCapture.reset();
        }

        long startTime = System.currentTimeMillis();
        try {
            ExportFunction func = wasmInstance.export(runExportName);
            runResults = func.apply(args);
        } catch (WasiExitException e) {
            runExecExitCode = e.exitCode();
            instanceNeedsReset = true;
        } catch (TrapException e) {
            runExecError = "Trap: " + e.getMessage();
        } catch (Exception e) {
            runExecError = "Error: " + e.getMessage();
        }
        runExecDurationMs = System.currentTimeMillis() - startTime;

        if (wasiStdoutCapture != null && wasiStdoutCapture.size() > 0) {
            runStdout = wasiStdoutCapture.toString(StandardCharsets.UTF_8);
        }
        if (wasiStderrCapture != null && wasiStderrCapture.size() > 0) {
            runStderr = wasiStderrCapture.toString(StandardCharsets.UTF_8);
        }

        inRunParamView = false;
        inRunOutputView = true;
        runOutputScrollOffset = 0;
    }

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

    private static List<String> buildFunctionNames(WasmModule module) {
        int importedFuncs = module.importSection().count(ExternalType.FUNCTION);
        int localFuncCount = module.functionSection().functionCount();
        var names = new ArrayList<String>(localFuncCount);

        // Initialize with name section names (uses absolute indices)
        var nameSection = module.nameSection();
        for (int i = 0; i < localFuncCount; i++) {
            int absIdx = importedFuncs + i;
            String name = null;
            if (nameSection != null) {
                name = nameSection.nameOfFunction(absIdx);
            }
            names.add(name);
        }

        // Fill in from exports where name section didn't have a name
        var es = module.exportSection();
        for (int i = 0; i < es.exportCount(); i++) {
            var exp = es.getExport(i);
            if (exp.exportType() == ExternalType.FUNCTION) {
                int localIdx = (int) exp.index() - importedFuncs;
                if (localIdx >= 0 && localIdx < localFuncCount && names.get(localIdx) == null) {
                    names.set(localIdx, exp.name());
                }
            }
        }

        // Demangle all names
        try (var demangler = new RustcDemangle()) {
            for (int i = 0; i < names.size(); i++) {
                var name = names.get(i);
                if (name != null) {
                    names.set(i, demangler.demangle(name));
                }
            }
        }

        return names;
    }

    private String functionName(int localFuncIdx) {
        if (functionNamesFuture.isDone() && !functionNamesFuture.isCompletedExceptionally()) {
            var names = functionNamesFuture.join();
            if (localFuncIdx >= 0 && localFuncIdx < names.size()) {
                var name = names.get(localFuncIdx);
                if (name != null) {
                    return name;
                }
            }
        }
        return "func #" + localFuncIdx;
    }

    private EventResult handleKey(KeyEvent key, ToolkitRunner runner) {
        if (key.isQuit()) {
            runner.quit();
            return EventResult.HANDLED;
        }

        if (inFunctionView) {
            if (inContentSearch) {
                return handleContentSearch(key, true);
            }
            if (key.isCancel() || key.isLeft()) {
                inFunctionView = false;
                contentSearchQuery = "";
                contentSearchMatchLine = -1;
                return EventResult.HANDLED;
            }
            if (key.isSelect() || key.isConfirm()) {
                showWatMode = !showWatMode;
                functionViewScrollOffset = 0;
                contentSearchQuery = "";
                contentSearchMatchLine = -1;
                return EventResult.HANDLED;
            }
            if (key.isChar('/')) {
                inContentSearch = true;
                contentSearchQuery = "";
                contentSearchMatchLine = -1;
                return EventResult.HANDLED;
            }
            if (key.isChar('n') && !contentSearchQuery.isEmpty()) {
                searchNextMatch(true);
                return EventResult.HANDLED;
            }
            if (key.isChar('N') && !contentSearchQuery.isEmpty()) {
                searchPrevMatch(true);
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                if (functionViewScrollOffset > 0) {
                    functionViewScrollOffset--;
                }
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                functionViewScrollOffset++;
                return EventResult.HANDLED;
            }
            if (key.isPageUp()) {
                functionViewScrollOffset = Math.max(0, functionViewScrollOffset - PAGE_SIZE);
                return EventResult.HANDLED;
            }
            if (key.isPageDown()) {
                functionViewScrollOffset += PAGE_SIZE;
                return EventResult.HANDLED;
            }
            if (key.isHome()) {
                functionViewScrollOffset = 0;
                return EventResult.HANDLED;
            }
            if (key.isEnd()) {
                functionViewScrollOffset = Integer.MAX_VALUE;
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        if (inDataView) {
            if (inContentSearch) {
                return handleContentSearch(key, false);
            }
            if (key.isCancel() || key.isLeft()) {
                inDataView = false;
                contentSearchQuery = "";
                contentSearchMatchLine = -1;
                return EventResult.HANDLED;
            }
            if (key.isChar('/')) {
                inContentSearch = true;
                contentSearchQuery = "";
                contentSearchMatchLine = -1;
                return EventResult.HANDLED;
            }
            if (key.isChar('n') && !contentSearchQuery.isEmpty()) {
                searchNextMatch(false);
                return EventResult.HANDLED;
            }
            if (key.isChar('N') && !contentSearchQuery.isEmpty()) {
                searchPrevMatch(false);
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                if (dataViewScrollOffset > 0) {
                    dataViewScrollOffset--;
                }
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                dataViewScrollOffset++;
                return EventResult.HANDLED;
            }
            if (key.isPageUp()) {
                dataViewScrollOffset = Math.max(0, dataViewScrollOffset - PAGE_SIZE);
                return EventResult.HANDLED;
            }
            if (key.isPageDown()) {
                dataViewScrollOffset += PAGE_SIZE;
                return EventResult.HANDLED;
            }
            if (key.isHome()) {
                dataViewScrollOffset = 0;
                return EventResult.HANDLED;
            }
            if (key.isEnd()) {
                dataViewScrollOffset = Integer.MAX_VALUE;
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        if (inRunOutputView) {
            if (key.isCancel() || key.isLeft()) {
                inRunOutputView = false;
                inDetailView = true;
                return EventResult.HANDLED;
            }
            if (key.isChar('r')) {
                // Re-run with same parameters
                if (ensureInstance()) {
                    long[] args = new long[0];
                    if (runParamValues != null && runParamValues.length > 0) {
                        args = new long[runParamValues.length];
                        for (int i = 0; i < runParamValues.length; i++) {
                            try {
                                args[i] = parseParam(runParamTypes.get(i), runParamValues[i]);
                            } catch (Exception e) {
                                return EventResult.HANDLED;
                            }
                        }
                    }
                    executeFunction(args);
                }
                return EventResult.HANDLED;
            }
            if (key.isChar('R')) {
                instanceNeedsReset = true;
                inRunOutputView = false;
                inDetailView = true;
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                if (runOutputScrollOffset > 0) runOutputScrollOffset--;
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                runOutputScrollOffset++;
                return EventResult.HANDLED;
            }
            if (key.isPageUp()) {
                runOutputScrollOffset = Math.max(0, runOutputScrollOffset - PAGE_SIZE);
                return EventResult.HANDLED;
            }
            if (key.isPageDown()) {
                runOutputScrollOffset += PAGE_SIZE;
                return EventResult.HANDLED;
            }
            if (key.isHome()) {
                runOutputScrollOffset = 0;
                return EventResult.HANDLED;
            }
            if (key.isEnd()) {
                runOutputScrollOffset = Integer.MAX_VALUE;
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        if (inRunParamView) {
            if (key.isCancel()) {
                inRunParamView = false;
                inDetailView = true;
                return EventResult.HANDLED;
            }
            if (key.isConfirm()) {
                // Validate and execute
                long[] args = new long[runParamTypes.size()];
                for (int i = 0; i < runParamTypes.size(); i++) {
                    try {
                        args[i] = parseParam(runParamTypes.get(i), runParamValues[i]);
                    } catch (Exception e) {
                        runParamError = "Invalid param " + i + ": " + e.getMessage();
                        return EventResult.HANDLED;
                    }
                }
                runParamError = null;
                executeFunction(args);
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                if (runParamFocusIdx > 0) runParamFocusIdx--;
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                if (runParamFocusIdx < runParamTypes.size() - 1) runParamFocusIdx++;
                return EventResult.HANDLED;
            }
            if (key.isDeleteBackward()) {
                if (!runParamValues[runParamFocusIdx].isEmpty()) {
                    runParamValues[runParamFocusIdx] =
                            runParamValues[runParamFocusIdx].substring(
                                    0, runParamValues[runParamFocusIdx].length() - 1);
                }
                runParamError = null;
                return EventResult.HANDLED;
            }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                runParamValues[runParamFocusIdx] += key.character();
                runParamError = null;
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        if (inMemoryView) {
            // Sub-mode: goto address
            if (inMemGoto) {
                if (key.isCancel()) {
                    inMemGoto = false;
                    memGotoInput = "";
                    return EventResult.HANDLED;
                }
                if (key.isConfirm()) {
                    if (!memGotoInput.isEmpty()) {
                        try {
                            int addr = Integer.parseUnsignedInt(memGotoInput);
                            Memory mem = wasmInstance.memory();
                            int maxAddr = mem.pages() * 65536;
                            if (addr >= 0 && addr < maxAddr) {
                                memViewAddress = (addr / 8) * 8;
                                memStatusMessage = null;
                            } else {
                                memStatusMessage = "Address out of range";
                            }
                        } catch (NumberFormatException e) {
                            memStatusMessage = "Invalid address";
                        }
                    }
                    inMemGoto = false;
                    memGotoInput = "";
                    return EventResult.HANDLED;
                }
                if (key.isDeleteBackward() && !memGotoInput.isEmpty()) {
                    memGotoInput = memGotoInput.substring(0, memGotoInput.length() - 1);
                    return EventResult.HANDLED;
                }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                    char c = key.character();
                    if (c >= '0' && c <= '9') {
                        memGotoInput += c;
                    }
                    return EventResult.HANDLED;
                }
                return EventResult.HANDLED;
            }
            // Sub-mode: write string (phase 1: address, phase 2: string)
            if (inMemWriteString) {
                if (key.isCancel()) {
                    inMemWriteString = false;
                    return EventResult.HANDLED;
                }
                if (memWriteAddrPhase) {
                    if (key.isConfirm() && !memWriteAddr.isEmpty()) {
                        memWriteAddrPhase = false;
                        return EventResult.HANDLED;
                    }
                    if (key.isDeleteBackward() && !memWriteAddr.isEmpty()) {
                        memWriteAddr = memWriteAddr.substring(0, memWriteAddr.length() - 1);
                        return EventResult.HANDLED;
                    }
                    if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                        char c = key.character();
                        if (c >= '0' && c <= '9') {
                            memWriteAddr += c;
                        }
                        return EventResult.HANDLED;
                    }
                } else {
                    if (key.isFocusNext() || key.isFocusPrevious()) {
                        memWriteNullTerm = !memWriteNullTerm;
                        return EventResult.HANDLED;
                    }
                    if (key.isConfirm()) {
                        if (!memWriteStringInput.isEmpty()) {
                            try {
                                int addr = Integer.parseUnsignedInt(memWriteAddr);
                                Memory mem = wasmInstance.memory();
                                byte[] strBytes =
                                        memWriteStringInput.getBytes(StandardCharsets.UTF_8);
                                byte[] bytes;
                                if (memWriteNullTerm) {
                                    bytes = new byte[strBytes.length + 1];
                                    System.arraycopy(strBytes, 0, bytes, 0, strBytes.length);
                                    // bytes[strBytes.length] is already 0
                                } else {
                                    bytes = strBytes;
                                }
                                mem.write(addr, bytes);
                                memViewAddress = (addr / 8) * 8;
                                memStatusMessage = "Wrote " + bytes.length + " bytes at "
                                        + addr;
                            } catch (Exception e) {
                                memStatusMessage = "Write failed: " + e.getMessage();
                            }
                        }
                        inMemWriteString = false;
                        return EventResult.HANDLED;
                    }
                    if (key.isDeleteBackward() && !memWriteStringInput.isEmpty()) {
                        memWriteStringInput = memWriteStringInput.substring(
                                0, memWriteStringInput.length() - 1);
                        return EventResult.HANDLED;
                    }
                    if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                        memWriteStringInput += key.character();
                        return EventResult.HANDLED;
                    }
                }
                return EventResult.HANDLED;
            }
            // Sub-mode: write typed value (phase 0: address, 1: type, 2: value)
            if (inMemWriteTyped) {
                if (key.isCancel()) {
                    inMemWriteTyped = false;
                    return EventResult.HANDLED;
                }
                if (memWriteTypedPhase == 0) {
                    if (key.isConfirm() && !memWriteTypedAddr.isEmpty()) {
                        memWriteTypedPhase = 1;
                        return EventResult.HANDLED;
                    }
                    if (key.isDeleteBackward() && !memWriteTypedAddr.isEmpty()) {
                        memWriteTypedAddr = memWriteTypedAddr.substring(
                                0, memWriteTypedAddr.length() - 1);
                        return EventResult.HANDLED;
                    }
                    if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                        char c = key.character();
                        if (c >= '0' && c <= '9') {
                            memWriteTypedAddr += c;
                        }
                        return EventResult.HANDLED;
                    }
                } else if (memWriteTypedPhase == 1) {
                    if (key.isLeft()) {
                        memWriteTypedTypeIdx =
                                (memWriteTypedTypeIdx - 1 + TYPED_VALUE_TYPES.length)
                                        % TYPED_VALUE_TYPES.length;
                        return EventResult.HANDLED;
                    }
                    if (key.isRight()) {
                        memWriteTypedTypeIdx =
                                (memWriteTypedTypeIdx + 1) % TYPED_VALUE_TYPES.length;
                        return EventResult.HANDLED;
                    }
                    if (key.isConfirm()) {
                        memWriteTypedPhase = 2;
                        return EventResult.HANDLED;
                    }
                } else {
                    if (key.isConfirm()) {
                        if (!memWriteTypedValue.isEmpty()) {
                            try {
                                int addr = Integer.parseUnsignedInt(memWriteTypedAddr);
                                byte[] bytes = encodeTypedValue(
                                        TYPED_VALUE_TYPES[memWriteTypedTypeIdx],
                                        memWriteTypedValue);
                                Memory mem = wasmInstance.memory();
                                mem.write(addr, bytes);
                                memViewAddress = (addr / 8) * 8;
                                memStatusMessage = "Wrote "
                                        + TYPED_VALUE_TYPES[memWriteTypedTypeIdx]
                                        + " (" + bytes.length + " bytes) at " + addr;
                            } catch (Exception e) {
                                memStatusMessage = "Write failed: " + e.getMessage();
                            }
                        }
                        inMemWriteTyped = false;
                        return EventResult.HANDLED;
                    }
                    if (key.isDeleteBackward() && !memWriteTypedValue.isEmpty()) {
                        memWriteTypedValue = memWriteTypedValue.substring(
                                0, memWriteTypedValue.length() - 1);
                        return EventResult.HANDLED;
                    }
                    if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                        memWriteTypedValue += key.character();
                        return EventResult.HANDLED;
                    }
                }
                return EventResult.HANDLED;
            }
            // Main memory view keys
            if (key.isCancel() || key.isLeft()) {
                inMemoryView = false;
                inDetailView = true;
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                memViewAddress = Math.max(0, memViewAddress - 8);
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                Memory mem = wasmInstance.memory();
                int maxAddr = mem.pages() * 65536;
                if (memViewAddress + 8 < maxAddr) {
                    memViewAddress += 8;
                }
                return EventResult.HANDLED;
            }
            if (key.isPageUp()) {
                int pageBytes = PAGE_SIZE * 8;
                memViewAddress = Math.max(0, memViewAddress - pageBytes);
                return EventResult.HANDLED;
            }
            if (key.isPageDown()) {
                Memory mem = wasmInstance.memory();
                int maxAddr = mem.pages() * 65536;
                int pageBytes = PAGE_SIZE * 8;
                memViewAddress = Math.min(maxAddr - 8, memViewAddress + pageBytes);
                if (memViewAddress < 0) memViewAddress = 0;
                return EventResult.HANDLED;
            }
            if (key.isHome()) {
                memViewAddress = 0;
                return EventResult.HANDLED;
            }
            if (key.isEnd()) {
                Memory mem = wasmInstance.memory();
                int maxAddr = mem.pages() * 65536;
                memViewAddress = Math.max(0, maxAddr - PAGE_SIZE * 8);
                return EventResult.HANDLED;
            }
            if (key.isChar('g')) {
                inMemGoto = true;
                memGotoInput = "";
                return EventResult.HANDLED;
            }
            if (key.isChar('w')) {
                inMemWriteString = true;
                memWriteAddrPhase = true;
                memWriteAddr = "";
                memWriteStringInput = "";
                memWriteNullTerm = false;
                return EventResult.HANDLED;
            }
            if (key.isChar('e')) {
                inMemWriteTyped = true;
                memWriteTypedPhase = 0;
                memWriteTypedAddr = "";
                memWriteTypedTypeIdx = 0;
                memWriteTypedValue = "";
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        // Detail pane navigation mode
        if (inDetailView) {
            // Search input mode
            if (inSearch) {
                if (key.isCancel()) {
                    inSearch = false;
                    searchFilter = "";
                    detailTableState.select(0);
                    return EventResult.HANDLED;
                }
                if (key.isConfirm()) {
                    inSearch = false;
                    return EventResult.HANDLED;
                }
                if (key.isDeleteBackward() && !searchFilter.isEmpty()) {
                    searchFilter = searchFilter.substring(0, searchFilter.length() - 1);
                    detailTableState.select(0);
                    return EventResult.HANDLED;
                }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                    char c = key.character();
                    if (Character.isDigit(c)) {
                        searchFilter += c;
                        detailTableState.select(0);
                        return EventResult.HANDLED;
                    }
                }
                return EventResult.HANDLED;
            }

            // Sub-mode: global edit
            if (inGlobalEdit) {
                if (key.isCancel()) {
                    inGlobalEdit = false;
                    globalEditError = null;
                    return EventResult.HANDLED;
                }
                if (key.isConfirm()) {
                    if (!globalEditValue.isEmpty()) {
                        try {
                            var gs = module.globalSection();
                            int importedGlobals =
                                    module.importSection().count(ExternalType.GLOBAL);
                            var g = gs.getGlobal(globalEditIdx);
                            var gi = wasmInstance.global(importedGlobals + globalEditIdx);
                            var type = g.valueType();
                            long raw = parseParam(type, globalEditValue);
                            gi.setValue(raw);
                            globalEditError = null;
                        } catch (Exception e) {
                            globalEditError = "Set failed: " + e.getMessage();
                        }
                    }
                    inGlobalEdit = false;
                    return EventResult.HANDLED;
                }
                if (key.isDeleteBackward() && !globalEditValue.isEmpty()) {
                    globalEditValue =
                            globalEditValue.substring(0, globalEditValue.length() - 1);
                    return EventResult.HANDLED;
                }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                    globalEditValue += key.character();
                    return EventResult.HANDLED;
                }
                return EventResult.HANDLED;
            }

            if (key.isChar('/')) {
                inSearch = true;
                searchFilter = "";
                detailTableState.select(0);
                return EventResult.HANDLED;
            }
            if (key.isCancel() || key.isLeft()) {
                inDetailView = false;
                searchFilter = "";
                return EventResult.HANDLED;
            }
            if (key.isUp()) {
                detailTableState.selectPrevious();
                return EventResult.HANDLED;
            }
            if (key.isDown()) {
                detailTableState.selectNext(filteredDetailRowCount(selectedSectionName()));
                return EventResult.HANDLED;
            }
            if (key.isPageUp()) {
                int current =
                        detailTableState.selected() != null ? detailTableState.selected() : 0;
                detailTableState.select(Math.max(0, current - PAGE_SIZE));
                return EventResult.HANDLED;
            }
            if (key.isPageDown()) {
                int current =
                        detailTableState.selected() != null ? detailTableState.selected() : 0;
                int count = filteredDetailRowCount(selectedSectionName());
                detailTableState.select(Math.min(count - 1, current + PAGE_SIZE));
                return EventResult.HANDLED;
            }
            if (key.isHome()) {
                detailTableState.selectFirst();
                return EventResult.HANDLED;
            }
            if (key.isEnd()) {
                detailTableState.selectLast(filteredDetailRowCount(selectedSectionName()));
                return EventResult.HANDLED;
            }
            if (key.isSelect() || key.isConfirm()) {
                var section = selectedSectionName();
                var detailIdx =
                        detailTableState.selected() != null ? detailTableState.selected() : 0;
                if ("Functions".equals(section)) {
                    // Jump to Code section, same function index
                    int codeIdx = sectionIndex("Code");
                    if (codeIdx >= 0 && detailIdx < module.codeSection().functionBodyCount()) {
                        tableState.select(codeIdx);
                        detailTableState.select(detailIdx);
                        searchFilter = "";
                        return EventResult.HANDLED;
                    }
                } else if ("Code".equals(section)
                        && module.codeSection().functionBodyCount() > 0) {
                    // Enter function view
                    inFunctionView = true;
                    selectedFunctionIdx = detailIdx;
                    showWatMode = true;
                    functionViewScrollOffset = 0;
                    inDetailView = false;
                    searchFilter = "";
                    return EventResult.HANDLED;
                } else if ("Imports".equals(section)) {
                    var is = module.importSection();
                    if (detailIdx < is.importCount()) {
                        var imp = is.getImport(detailIdx);
                        if (imp instanceof FunctionImport funcImport) {
                            int typesIdx = sectionIndex("Types");
                            if (typesIdx >= 0) {
                                tableState.select(typesIdx);
                                detailTableState.select(funcImport.typeIndex());
                                searchFilter = "";
                                return EventResult.HANDLED;
                            }
                        }
                    }
                } else if ("Exports".equals(section)) {
                    var es = module.exportSection();
                    if (detailIdx < es.exportCount()) {
                        var exp = es.getExport(detailIdx);
                        if (exp.exportType() == ExternalType.FUNCTION) {
                            int importedFuncs =
                                    module.importSection().count(ExternalType.FUNCTION);
                            int localIdx = (int) exp.index() - importedFuncs;
                            if (localIdx >= 0
                                    && localIdx
                                            < module.codeSection().functionBodyCount()) {
                                inFunctionView = true;
                                selectedFunctionIdx = localIdx;
                                showWatMode = true;
                                functionViewScrollOffset = 0;
                                inDetailView = false;
                                searchFilter = "";
                                return EventResult.HANDLED;
                            }
                        } else if (exp.exportType() == ExternalType.MEMORY) {
                            if (ensureInstance()) {
                                inMemoryView = true;
                                memViewAddress = 0;
                                memStatusMessage = null;
                                inDetailView = false;
                                searchFilter = "";
                                return EventResult.HANDLED;
                            } else {
                                memStatusMessage = instanceError;
                            }
                        }
                    }
                } else if ("Data".equals(section)) {
                    var ds = module.dataSection();
                    if (detailIdx < ds.dataSegmentCount()) {
                        inDataView = true;
                        selectedDataIdx = detailIdx;
                        dataViewScrollOffset = 0;
                        inDetailView = false;
                        searchFilter = "";
                        return EventResult.HANDLED;
                    }
                } else if ("Memories".equals(section)) {
                    if (ensureInstance()) {
                        inMemoryView = true;
                        memStatusMessage = null;
                        inDetailView = false;
                        searchFilter = "";
                        return EventResult.HANDLED;
                    } else {
                        memStatusMessage = instanceError;
                    }
                } else if ("Globals".equals(section)) {
                    var gs = module.globalSection();
                    if (detailIdx < gs.globalCount()
                            && gs.getGlobal(detailIdx).mutabilityType()
                                    == MutabilityType.Var) {
                        if (ensureInstance()) {
                            inGlobalEdit = true;
                            globalEditIdx = detailIdx;
                            globalEditValue = "";
                            globalEditError = null;
                        } else {
                            globalEditError = instanceError;
                        }
                        return EventResult.HANDLED;
                    }
                }
            }
            // 'r' in Exports detail → run exported function
            if (key.isChar('r') && "Exports".equals(selectedSectionName())) {
                handleRunExport();
                return EventResult.HANDLED;
            }
            // 'f' in Code detail → jump to Functions signature
            if (key.isChar('f') && "Code".equals(selectedSectionName())) {
                var detailIdx =
                        detailTableState.selected() != null ? detailTableState.selected() : 0;
                int funcIdx = sectionIndex("Functions");
                if (funcIdx >= 0 && detailIdx < module.functionSection().functionCount()) {
                    tableState.select(funcIdx);
                    detailTableState.select(detailIdx);
                    searchFilter = "";
                    return EventResult.HANDLED;
                }
            }
            // 'c' in Functions detail → jump to Code
            if (key.isChar('c') && "Functions".equals(selectedSectionName())) {
                var detailIdx =
                        detailTableState.selected() != null ? detailTableState.selected() : 0;
                int codeIdx = sectionIndex("Code");
                if (codeIdx >= 0 && detailIdx < module.codeSection().functionBodyCount()) {
                    tableState.select(codeIdx);
                    detailTableState.select(detailIdx);
                    searchFilter = "";
                    return EventResult.HANDLED;
                }
            }
            return EventResult.UNHANDLED;
        }

        // Section navigation mode
        if (key.isCancel()) {
            runner.quit();
            return EventResult.HANDLED;
        }
        if (key.isUp()) {
            tableState.selectPrevious();
            return EventResult.HANDLED;
        }
        if (key.isDown()) {
            tableState.selectNext(sectionRows.size());
            return EventResult.HANDLED;
        }
        if (key.isHome()) {
            tableState.selectFirst();
            return EventResult.HANDLED;
        }
        if (key.isEnd()) {
            tableState.selectLast(sectionRows.size());
            return EventResult.HANDLED;
        }
        if (key.isRight()) {
            int count = detailRowCount(selectedSectionName());
            if (count > 0) {
                inDetailView = true;
                detailTableState.select(0);
                return EventResult.HANDLED;
            }
        }
        if (key.isSelect() || key.isConfirm()) {
            if ("Code".equals(selectedSectionName())
                    && module.codeSection().functionBodyCount() > 0) {
                inFunctionView = true;
                selectedFunctionIdx = 0;
                showWatMode = true;
                functionViewScrollOffset = 0;
                return EventResult.HANDLED;
            }
        }
        return EventResult.UNHANDLED;
    }

    private int sectionIndex(String name) {
        for (int i = 0; i < sectionRows.size(); i++) {
            if (name.equals(sectionRows.get(i)[0])) {
                return i;
            }
        }
        return -1;
    }

    private String selectedSectionName() {
        var idx = tableState.selected() != null ? tableState.selected() : 0;
        return idx < sectionRows.size() ? sectionRows.get(idx)[0] : "";
    }

    private int detailRowCount(String sectionName) {
        return switch (sectionName) {
            case "Types" -> module.typeSection().typeCount();
            case "Imports" -> module.importSection().importCount();
            case "Functions" -> module.functionSection().functionCount();
            case "Tables" -> module.tableSection().tableCount();
            case "Memories" -> module.memorySection().map(m -> m.memoryCount()).orElse(0);
            case "Globals" -> module.globalSection().globalCount();
            case "Exports" -> module.exportSection().exportCount();
            case "Elements" -> module.elementSection().elementCount();
            case "Code" -> module.codeSection().functionBodyCount();
            case "Data" -> module.dataSection().dataSegmentCount();
            default -> 0;
        };
    }

    private boolean matchesFilter(int index) {
        return searchFilter.isEmpty() || String.valueOf(index).startsWith(searchFilter);
    }

    private int filteredDetailRowCount(String sectionName) {
        int total = detailRowCount(sectionName);
        if (searchFilter.isEmpty()) {
            return total;
        }
        int count = 0;
        for (int i = 0; i < total; i++) {
            if (matchesFilter(i)) {
                count++;
            }
        }
        return count;
    }

    private Element render() {
        var sections =
                table()
                        .header("Section", "Count")
                        .widths(length(12), length(8))
                        .columnSpacing(1)
                        .state(tableState)
                        .highlightSymbol("\u25b6 ")
                        .highlightStyle(Style.EMPTY.bg(Color.LIGHT_BLUE).fg(Color.BLACK));

        for (var row : sectionRows) {
            sections.row(row);
        }

        // TODO: center the title once upstream tamboui supports title alignment propagation
        var titleBar =
                row(
                        column(
                                text(" \u257b \u257b \u250f\u2501\u2513 \u250f\u2501\u2578")
                                        .fg(Color.LIGHT_CYAN)
                                        .bold(),
                                text(" \u2503\u257b\u2503 \u2517\u2501\u252b \u2517\u2501\u2513")
                                        .cyan()
                                        .bold(),
                                text(" \u2517\u253b\u251b \u257a\u2501\u251b \u257a\u2501\u251b")
                                        .fg(Color.LIGHT_BLUE)
                                        .bold())
                                .length(12),
                        panel()
                                .title(filename)
                                .rounded()
                                .borderColor(Color.CYAN)
                                .fill(1))
                        .length(3);

        if (inFunctionView) {
            int funcCount = module.codeSection().functionBodyCount();
            var funcName = functionName(selectedFunctionIdx);
            var funcTitle =
                    funcName + " (" + (selectedFunctionIdx + 1) + "/" + funcCount + ")";
            var modeLabel = showWatMode ? "WAT" : "Hex";
            var modeBorder = showWatMode ? Color.MAGENTA : Color.CYAN;
            var contentView = showWatMode
                    ? renderWatView(selectedFunctionIdx)
                    : renderHexView(selectedFunctionIdx);
            var panelTitle = modeLabel + " - " + funcTitle;
            if (!contentSearchQuery.isEmpty() && !inContentSearch) {
                panelTitle += " [/" + contentSearchQuery + "]";
            }
            Element funcHelp;
            if (inContentSearch) {
                funcHelp = row(
                        text(" /: " + contentSearchQuery + "_").cyan().fit(),
                        text("  ESC").cyan().fit(),
                        text(" cancel  ").dim().fit(),
                        text("Enter").cyan().fit(),
                        text(" find").dim().fit());
            } else {
                funcHelp = row(
                        text(" \u2191\u2193").cyan().fit(),
                        text(" scroll  ").dim().fit(),
                        text("PgUp/Dn").cyan().fit(),
                        text(" page  ").dim().fit(),
                        text("/").cyan().fit(),
                        text(" search  ").dim().fit(),
                        text("n/N").cyan().fit(),
                        text(" next/prev  ").dim().fit(),
                        text("Enter").cyan().fit(),
                        text(" hex/WAT  ").dim().fit(),
                        text("ESC/\u2190").cyan().fit(),
                        text(" back").dim().fit());
            }
            return column(
                            titleBar,
                            row(
                                    panel(() -> sections)
                                            .title("Sections")
                                            .bottomTitle("\u2191\u2193 navigate")
                                            .rounded()
                                            .borderColor(Color.DARK_GRAY)
                                            .length(28),
                                    panel(() -> contentView)
                                            .title(panelTitle)
                                            .bottomTitle(functionSignature(selectedFunctionIdx))
                                            .rounded()
                                            .borderColor(modeBorder)
                                            .fill(1))
                                    .fill(),
                            panel(funcHelp)
                                    .rounded()
                                    .borderColor(Color.DARK_GRAY)
                                    .length(3))
                    .fill();
        }

        if (inDataView) {
            var ds = module.dataSection();
            int dataCount = ds.dataSegmentCount();
            var dataTitle =
                    "data #" + selectedDataIdx + " (" + (selectedDataIdx + 1) + "/"
                            + dataCount + ")";
            var seg = ds.getDataSegment(selectedDataIdx);
            var hexContent = renderDataHexView(seg.data());
            var dataPanelTitle = "Hex - " + dataTitle;
            if (!contentSearchQuery.isEmpty() && !inContentSearch) {
                dataPanelTitle += " [/" + contentSearchQuery + "]";
            }
            Element dataHelp;
            if (inContentSearch) {
                dataHelp = row(
                        text(" /: " + contentSearchQuery + "_").cyan().fit(),
                        text("  ESC").cyan().fit(),
                        text(" cancel  ").dim().fit(),
                        text("Enter").cyan().fit(),
                        text(" find").dim().fit());
            } else {
                dataHelp = row(
                        text(" \u2191\u2193").cyan().fit(),
                        text(" scroll  ").dim().fit(),
                        text("PgUp/Dn").cyan().fit(),
                        text(" page  ").dim().fit(),
                        text("/").cyan().fit(),
                        text(" search  ").dim().fit(),
                        text("n/N").cyan().fit(),
                        text(" next/prev  ").dim().fit(),
                        text("ESC/\u2190").cyan().fit(),
                        text(" back").dim().fit());
            }
            return column(
                            titleBar,
                            row(
                                    panel(() -> sections)
                                            .title("Sections")
                                            .bottomTitle("\u2191\u2193 navigate")
                                            .rounded()
                                            .borderColor(Color.DARK_GRAY)
                                            .length(28),
                                    panel(() -> hexContent)
                                            .title(dataPanelTitle)
                                            .bottomTitle(seg.data().length + " bytes")
                                            .rounded()
                                            .borderColor(Color.CYAN)
                                            .fill(1))
                                    .fill(),
                            panel(dataHelp)
                                    .rounded()
                                    .borderColor(Color.DARK_GRAY)
                                    .length(3))
                    .fill();
        }

        if (inRunOutputView) {
            return renderRunOutputLayout(titleBar, sections);
        }

        if (inRunParamView) {
            return renderRunParamLayout(titleBar, sections);
        }

        if (inMemoryView) {
            return renderMemoryLayout(titleBar, sections);
        }

        var selectedName = selectedSectionName();

        var sectionsBorder = inDetailView ? Color.DARK_GRAY : Color.GREEN;
        var detailBorder = inDetailView ? Color.MAGENTA : Color.DARK_GRAY;
        Element helpContent;
        if (inSearch) {
            helpContent =
                    row(
                            text(" /: " + searchFilter + "_").cyan().fit(),
                            text("  ESC").cyan().fit(),
                            text(" cancel  ").dim().fit(),
                            text("Enter").cyan().fit(),
                            text(" confirm").dim().fit());
        } else if (inDetailView) {
            if ("Code".equals(selectedName)) {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter  ").dim().fit(),
                                text("Enter").cyan().fit(),
                                text(" view func").dim().fit());
            } else if ("Functions".equals(selectedName)) {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter  ").dim().fit(),
                                text("Enter").cyan().fit(),
                                text(" \u2192 Code").dim().fit());
            } else if ("Imports".equals(selectedName)) {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter  ").dim().fit(),
                                text("Enter").cyan().fit(),
                                text(" \u2192 Type").dim().fit());
            } else if ("Exports".equals(selectedName)) {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter  ").dim().fit(),
                                text("r").cyan().fit(),
                                text(" run  ").dim().fit(),
                                text("Enter").cyan().fit(),
                                text(" view/edit").dim().fit());
            } else if ("Data".equals(selectedName)) {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter  ").dim().fit(),
                                text("Enter").cyan().fit(),
                                text(" view hex").dim().fit());
            } else if ("Globals".equals(selectedName)) {
                if (inGlobalEdit) {
                    helpContent =
                            row(
                                    text(" Enter").cyan().fit(),
                                    text(" confirm  ").dim().fit(),
                                    text("ESC").cyan().fit(),
                                    text(" cancel").dim().fit());
                } else {
                    helpContent =
                            row(
                                    text(" ESC/\u2190").cyan().fit(),
                                    text(" back  ").dim().fit(),
                                    text("\u2191\u2193").cyan().fit(),
                                    text(" scroll  ").dim().fit(),
                                    text("/").cyan().fit(),
                                    text(" filter  ").dim().fit(),
                                    text("Enter").cyan().fit(),
                                    text(" edit").dim().fit());
                }
            } else {
                helpContent =
                        row(
                                text(" ESC/\u2190").cyan().fit(),
                                text(" back  ").dim().fit(),
                                text("\u2191\u2193").cyan().fit(),
                                text(" scroll  ").dim().fit(),
                                text("/").cyan().fit(),
                                text(" filter").dim().fit());
            }
        } else {
            helpContent =
                    row(
                            text(" q/ESC").cyan().fit(),
                            text(" quit  ").dim().fit(),
                            text("\u2191\u2193").cyan().fit(),
                            text(" navigate  ").dim().fit(),
                            text("\u2192").cyan().fit(),
                            text(" detail").dim().fit());
        }

        var detailTitle = selectedName;
        if (inDetailView && !searchFilter.isEmpty() && !inSearch) {
            detailTitle = selectedName + " [/" + searchFilter + "]";
        }

        return column(
                        titleBar,
                        row(
                                panel(() -> sections)
                                        .title("Sections")
                                        .bottomTitle("\u2191\u2193 navigate")
                                        .rounded()
                                        .borderColor(sectionsBorder)
                                        .length(28),
                                panel(() -> renderDetail(selectedName))
                                        .title(detailTitle)
                                        .rounded()
                                        .borderColor(detailBorder)
                                        .fill(1))
                                .fill(),
                        panel(helpContent)
                                .rounded()
                                .borderColor(Color.DARK_GRAY)
                                .length(3))
                .fill();
    }

    // === Run Output Layout ===

    private Element renderRunOutputLayout(Element titleBar, TableElement sections) {
        boolean hasError = runExecError != null;
        boolean hasWasiExit = runExecExitCode >= 0;
        var borderColor = (hasError || (hasWasiExit && runExecExitCode != 0))
                ? Color.RED : Color.GREEN;

        var sb = new StringBuilder();
        sb.append("Run: ").append(runExportName).append('\n');

        // Input parameters
        if (runParamValues != null && runParamValues.length > 0) {
            sb.append("\n-- Input Parameters --\n");
            for (int i = 0; i < runParamValues.length; i++) {
                sb.append("  param ").append(i)
                        .append(" (").append(runParamTypes.get(i)).append("): ")
                        .append(runParamValues[i]).append('\n');
            }
        }

        // Return values
        if (runResults != null && runResults.length > 0) {
            sb.append("\n-- Return Values --\n");
            for (int i = 0; i < runResults.length; i++) {
                var type = i < runReturnTypes.size() ? runReturnTypes.get(i) : ValType.I64;
                sb.append("  result ").append(i)
                        .append(" (").append(type).append("): ")
                        .append(formatReturnValue(type, runResults[i])).append('\n');
            }
        }

        // Status
        sb.append("\n-- Status --\n");
        if (hasError) {
            sb.append("  ").append(runExecError).append('\n');
        } else if (hasWasiExit) {
            sb.append("  WASI exit code: ").append(runExecExitCode).append('\n');
        } else {
            sb.append("  Completed");
        }
        sb.append("  Duration: ").append(runExecDurationMs).append("ms\n");

        // Stdout
        if (!runStdout.isEmpty()) {
            sb.append("\n-- stdout --\n");
            sb.append(runStdout);
            if (!runStdout.endsWith("\n")) sb.append('\n');
        }

        // Stderr
        if (!runStderr.isEmpty()) {
            sb.append("\n-- stderr --\n");
            sb.append(runStderr);
            if (!runStderr.endsWith("\n")) sb.append('\n');
        }

        var content = sb.toString();
        var scrolled = scrollContent(content, runOutputScrollOffset);

        var helpBar = row(
                text(" r").cyan().fit(),
                text(" re-run  ").dim().fit(),
                text("R").cyan().fit(),
                text(" reset  ").dim().fit(),
                text("\u2191\u2193").cyan().fit(),
                text(" scroll  ").dim().fit(),
                text("ESC/\u2190").cyan().fit(),
                text(" back").dim().fit());

        return column(
                        titleBar,
                        row(
                                panel(() -> sections)
                                        .title("Sections")
                                        .bottomTitle("\u2191\u2193 navigate")
                                        .rounded()
                                        .borderColor(Color.DARK_GRAY)
                                        .length(28),
                                panel(richText(scrolled).overflow(Overflow.CLIP).fill())
                                        .title("Run: " + runExportName)
                                        .rounded()
                                        .borderColor(borderColor)
                                        .fill(1))
                                .fill(),
                        panel(helpBar)
                                .rounded()
                                .borderColor(Color.DARK_GRAY)
                                .length(3))
                .fill();
    }

    // === Run Param Input Layout ===

    private Element renderRunParamLayout(Element titleBar, TableElement sections) {
        var sb = new StringBuilder();
        sb.append("Run: ").append(runExportName).append(" - Enter Parameters\n");
        sb.append("Signature: ").append(runParamTypes).append(" -> ")
                .append(runReturnTypes).append('\n');
        sb.append('\n');

        for (int i = 0; i < runParamTypes.size(); i++) {
            var prefix = (i == runParamFocusIdx) ? ">> " : "   ";
            var cursor = (i == runParamFocusIdx) ? "_" : "";
            sb.append(prefix).append("param ").append(i)
                    .append(" (").append(runParamTypes.get(i)).append("): ")
                    .append(runParamValues[i]).append(cursor).append('\n');
        }

        if (runParamError != null) {
            sb.append("\n  Error: ").append(runParamError).append('\n');
        }
        sb.append("\n  Enter to execute, ESC to cancel\n");

        var helpBar = row(
                text(" \u2191\u2193").cyan().fit(),
                text(" navigate  ").dim().fit(),
                text("Enter").cyan().fit(),
                text(" execute  ").dim().fit(),
                text("ESC").cyan().fit(),
                text(" cancel").dim().fit());

        return column(
                        titleBar,
                        row(
                                panel(() -> sections)
                                        .title("Sections")
                                        .bottomTitle("\u2191\u2193 navigate")
                                        .rounded()
                                        .borderColor(Color.DARK_GRAY)
                                        .length(28),
                                panel(richText(sb.toString()).overflow(Overflow.CLIP).fill())
                                        .title("Parameters")
                                        .rounded()
                                        .borderColor(Color.YELLOW)
                                        .fill(1))
                                .fill(),
                        panel(helpBar)
                                .rounded()
                                .borderColor(Color.DARK_GRAY)
                                .length(3))
                .fill();
    }

    // === Memory Editor Layout ===

    private Element renderMemoryLayout(Element titleBar, TableElement sections) {
        Memory mem = wasmInstance.memory();
        int totalBytes = mem.pages() * 65536;

        // Build hex dump from live memory
        int bytesPerLine = 8;
        int visibleLines = PAGE_SIZE + 5;
        int windowSize = Math.min(visibleLines * bytesPerLine, totalBytes - memViewAddress);
        if (windowSize <= 0) windowSize = 0;

        var sb = new StringBuilder();
        sb.append(String.format("Address   00 01 02 03 04 05 06 07  ASCII%n"));

        if (windowSize > 0) {
            byte[] data = mem.readBytes(memViewAddress, windowSize);
            for (int i = 0; i < data.length; i += bytesPerLine) {
                int addr = memViewAddress + i;
                sb.append(String.format("%08x  ", addr));
                int lineLen = Math.min(bytesPerLine, data.length - i);
                for (int j = 0; j < bytesPerLine; j++) {
                    if (j < lineLen) {
                        sb.append(String.format("%02X ", data[i + j] & 0xFF));
                    } else {
                        sb.append("   ");
                    }
                }
                sb.append(' ');
                for (int j = 0; j < lineLen; j++) {
                    int b = data[i + j] & 0xFF;
                    sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                }
                sb.append('\n');
            }
        }

        var memTitle = String.format("Memory (%d pages, %d bytes)", mem.pages(), totalBytes);

        // Sub-mode prompt as a separate panel
        String promptText = null;
        if (inMemGoto) {
            promptText = String.format("Go to address: %s_", memGotoInput);
        } else if (inMemWriteString) {
            if (memWriteAddrPhase) {
                promptText = String.format("Write string at address: %s_", memWriteAddr);
            } else {
                promptText = String.format("Write string at %s [%s] (Tab to toggle): %s_",
                        memWriteAddr,
                        memWriteNullTerm ? "null-terminated" : "raw",
                        memWriteStringInput);
            }
        } else if (inMemWriteTyped) {
            if (memWriteTypedPhase == 0) {
                promptText = String.format("Write value at address: %s_", memWriteTypedAddr);
            } else if (memWriteTypedPhase == 1) {
                promptText = String.format(
                        "Type: \u25c4 %s \u25ba (\u2190/\u2192 to change, Enter to confirm)",
                        TYPED_VALUE_TYPES[memWriteTypedTypeIdx]);
            } else {
                promptText = String.format("Write %s at %s: %s_",
                        TYPED_VALUE_TYPES[memWriteTypedTypeIdx],
                        memWriteTypedAddr,
                        memWriteTypedValue);
            }
        }
        if (memStatusMessage != null) {
            promptText = (promptText != null ? promptText + "\n" : "") + memStatusMessage;
        }

        Element helpBar;
        if (inMemGoto || inMemWriteString || inMemWriteTyped) {
            helpBar = row(
                    text(" Enter").cyan().fit(),
                    text(" confirm  ").dim().fit(),
                    text("ESC").cyan().fit(),
                    text(" cancel").dim().fit());
        } else {
            helpBar = row(
                    text(" g").cyan().fit(),
                    text(" goto  ").dim().fit(),
                    text("w").cyan().fit(),
                    text(" write-string  ").dim().fit(),
                    text("e").cyan().fit(),
                    text(" write-value  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(),
                    text(" scroll  ").dim().fit(),
                    text("ESC/\u2190").cyan().fit(),
                    text(" back").dim().fit());
        }

        var memContent =
                column(
                        panel(richText(sb.toString()).overflow(Overflow.CLIP).fill())
                                .title(memTitle)
                                .rounded()
                                .borderColor(Color.CYAN)
                                .fill(1),
                        promptText != null
                                ? panel(text(promptText).yellow().fit())
                                        .rounded()
                                        .borderColor(Color.YELLOW)
                                        .length(3 + (promptText.contains("\n") ? 1 : 0))
                                : text("").fit())
                        .fill(1);

        return column(
                        titleBar,
                        row(
                                panel(() -> sections)
                                        .title("Sections")
                                        .bottomTitle("\u2191\u2193 navigate")
                                        .rounded()
                                        .borderColor(Color.DARK_GRAY)
                                        .length(28),
                                memContent)
                                .fill(),
                        panel(helpBar)
                                .rounded()
                                .borderColor(Color.DARK_GRAY)
                                .length(3))
                .fill();
    }

    private Element renderHexView(int funcIdx) {
        if (funcIdx >= functionBodies.size()) {
            return text("No hex data available").dim();
        }
        var hex = formatHex(functionBodies.get(funcIdx));
        return richText(scrollContent(hex, functionViewScrollOffset))
                .overflow(Overflow.CLIP)
                .fill();
    }

    private Element renderDataHexView(byte[] data) {
        if (data.length == 0) {
            return text("No data available").dim();
        }
        var hex = formatHex(data);
        return richText(scrollContent(hex, dataViewScrollOffset))
                .overflow(Overflow.CLIP)
                .fill();
    }

    private Element renderWatView(int funcIdx) {
        if (!functionWatsFuture.isDone()) {
            return text("Loading WAT...").dim();
        }
        var wats = functionWatsFuture.join();
        if (funcIdx >= wats.size()) {
            return text("No WAT data available").dim();
        }
        var cached = highlightedWatCache.get(funcIdx);
        if (cached != null) {
            var scrolled = scrollAnsiContent(cached, functionViewScrollOffset);
            var parsed = parseAnsiText(scrolled);
            return richText(parsed).overflow(Overflow.CLIP).fill();
        }
        if (highlighterReady.isDone() && !highlighterReady.isCompletedExceptionally()) {
            var rawWat = wats.get(funcIdx);
            var result = watHighlighter.highlight(rawWat);
            if (result.success()) {
                var numbered = addLineNumbers(result.string());
                highlightedWatCache.put(funcIdx, numbered);
                var scrolled = scrollAnsiContent(numbered, functionViewScrollOffset);
                var parsed = parseAnsiText(scrolled);
                return richText(parsed).overflow(Overflow.CLIP).fill();
            }
        }
        // Fallback to uncolored (highlighter not ready or failed)
        var wat = formatWat(wats.get(funcIdx));
        return richText(scrollContent(wat, functionViewScrollOffset))
                .overflow(Overflow.CLIP)
                .fill();
    }

    private String functionSignature(int funcIdx) {
        if (funcIdx < module.functionSection().functionCount()) {
            var typeIdx = module.functionSection().getFunctionType(funcIdx);
            var ft = module.typeSection().getType(typeIdx);
            return ft.params() + " \u2192 " + ft.returns();
        }
        return "";
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

    static String addLineNumbers(String ansiContent) {
        var lines = ansiContent.split("\n", -1);
        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(String.format("%4d | %s", i + 1, lines[i]));
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

    static Text parseAnsiText(String ansiString) {
        var lines = new ArrayList<Line>();
        var spans = new ArrayList<Span>();
        var currentText = new StringBuilder();
        var currentStyle = Style.EMPTY;

        int i = 0;
        while (i < ansiString.length()) {
            if (i < ansiString.length() - 1
                    && ansiString.charAt(i) == '\033'
                    && ansiString.charAt(i + 1) == '[') {
                // Flush current text as a span
                if (!currentText.isEmpty()) {
                    spans.add(Span.styled(currentText.toString(), currentStyle));
                    currentText.setLength(0);
                }
                // Parse SGR sequence
                int end = ansiString.indexOf('m', i + 2);
                if (end < 0) {
                    // Malformed sequence, output as text
                    currentText.append(ansiString.charAt(i));
                    i++;
                    continue;
                }
                var params = ansiString.substring(i + 2, end);
                currentStyle = applySgr(currentStyle, params);
                i = end + 1;
            } else if (ansiString.charAt(i) == '\n') {
                // Flush span and line
                if (!currentText.isEmpty()) {
                    spans.add(Span.styled(currentText.toString(), currentStyle));
                    currentText.setLength(0);
                }
                lines.add(Line.from(List.copyOf(spans)));
                spans.clear();
                i++;
            } else {
                currentText.append(ansiString.charAt(i));
                i++;
            }
        }
        // Flush remaining
        if (!currentText.isEmpty()) {
            spans.add(Span.styled(currentText.toString(), currentStyle));
        }
        if (!spans.isEmpty()) {
            lines.add(Line.from(List.copyOf(spans)));
        }
        if (lines.isEmpty()) {
            lines.add(Line.empty());
        }
        return Text.from(lines);
    }

    private static Style applySgr(Style style, String params) {
        if (params.isEmpty()) {
            return Style.EMPTY;
        }
        var parts = params.split(";");
        int idx = 0;
        while (idx < parts.length) {
            int code;
            try {
                code = Integer.parseInt(parts[idx]);
            } catch (NumberFormatException e) {
                idx++;
                continue;
            }
            switch (code) {
                case 0 -> style = Style.EMPTY;
                case 1 -> style = style.bold();
                case 3 -> style = style.italic();
                case 38 -> {
                    // Foreground color: 38;2;r;g;b
                    if (idx + 4 < parts.length && "2".equals(parts[idx + 1])) {
                        try {
                            int r = Integer.parseInt(parts[idx + 2]);
                            int g = Integer.parseInt(parts[idx + 3]);
                            int b = Integer.parseInt(parts[idx + 4]);
                            style = style.fg(Color.rgb(r, g, b));
                        } catch (NumberFormatException e) {
                            // skip malformed
                        }
                        idx += 4;
                    }
                }
                default -> {} // ignore unsupported codes
            }
            idx++;
        }
        return style;
    }

    private EventResult handleContentSearch(KeyEvent key, boolean isFunctionView) {
        if (key.isCancel()) {
            inContentSearch = false;
            contentSearchQuery = "";
            contentSearchMatchLine = -1;
            return EventResult.HANDLED;
        }
        if (key.isConfirm()) {
            inContentSearch = false;
            if (!contentSearchQuery.isEmpty()) {
                searchNextMatch(isFunctionView);
            }
            return EventResult.HANDLED;
        }
        if (key.isDeleteBackward() && !contentSearchQuery.isEmpty()) {
            contentSearchQuery = contentSearchQuery.substring(0, contentSearchQuery.length() - 1);
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            contentSearchQuery += key.character();
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private String currentContentText(boolean isFunctionView) {
        if (isFunctionView) {
            if (showWatMode) {
                if (!functionWatsFuture.isDone()) return "";
                var wats = functionWatsFuture.join();
                if (selectedFunctionIdx >= wats.size()) return "";
                return formatWat(wats.get(selectedFunctionIdx));
            } else {
                if (selectedFunctionIdx >= functionBodies.size()) return "";
                return formatHex(functionBodies.get(selectedFunctionIdx));
            }
        } else {
            var ds = module.dataSection();
            if (selectedDataIdx >= ds.dataSegmentCount()) return "";
            return formatHex(ds.getDataSegment(selectedDataIdx).data());
        }
    }

    private void searchNextMatch(boolean isFunctionView) {
        var content = currentContentText(isFunctionView);
        var lines = content.split("\n", -1);
        var query = contentSearchQuery.toLowerCase();
        int startLine = (isFunctionView ? functionViewScrollOffset : dataViewScrollOffset) + 1;
        for (int i = startLine; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                if (isFunctionView) {
                    functionViewScrollOffset = i;
                } else {
                    dataViewScrollOffset = i;
                }
                return;
            }
        }
        // Wrap around from the beginning
        for (int i = 0; i < startLine && i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                if (isFunctionView) {
                    functionViewScrollOffset = i;
                } else {
                    dataViewScrollOffset = i;
                }
                return;
            }
        }
        contentSearchMatchLine = -1;
    }

    private void searchPrevMatch(boolean isFunctionView) {
        var content = currentContentText(isFunctionView);
        var lines = content.split("\n", -1);
        var query = contentSearchQuery.toLowerCase();
        int startLine = (isFunctionView ? functionViewScrollOffset : dataViewScrollOffset) - 1;
        for (int i = startLine; i >= 0; i--) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                if (isFunctionView) {
                    functionViewScrollOffset = i;
                } else {
                    dataViewScrollOffset = i;
                }
                return;
            }
        }
        // Wrap around from the end
        for (int i = lines.length - 1; i > startLine; i--) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                if (isFunctionView) {
                    functionViewScrollOffset = i;
                } else {
                    dataViewScrollOffset = i;
                }
                return;
            }
        }
        contentSearchMatchLine = -1;
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

    private void applyDetailHighlight(TableElement t) {
        if (inDetailView) {
            t.state(detailTableState)
                    .highlightSymbol(">> ")
                    .highlightStyle(Style.EMPTY.bg(Color.MAGENTA).fg(Color.BLACK));
        }
    }

    private Element renderDetail(String sectionName) {
        return switch (sectionName) {
            case "Types" -> renderTypes();
            case "Imports" -> renderImports();
            case "Functions" -> renderFunctions();
            case "Tables" -> renderTables();
            case "Memories" -> renderMemories();
            case "Globals" -> renderGlobals();
            case "Exports" -> renderExports();
            case "Start" -> renderStart();
            case "Elements" -> renderElements();
            case "Code" -> renderCode();
            case "Data" -> renderData();
            default -> text("Select a section").dim();
        };
    }

    private Element renderTypes() {
        var t =
                table()
                        .header("#", "Params", "Returns")
                        .widths(length(5), fill(1), fill(1))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var ts = module.typeSection();
        for (int i = 0; i < ts.typeCount(); i++) {
            if (!matchesFilter(i)) continue;
            var ft = ts.getType(i);
            t.row(
                    String.valueOf(i),
                    ft.params().toString(),
                    ft.returns().toString());
        }
        return t;
    }

    private Element renderImports() {
        var t =
                table()
                        .header("#", "Module", "Name", "Kind")
                        .widths(length(5), fill(1), fill(1), length(10))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var is = module.importSection();
        for (int i = 0; i < is.importCount(); i++) {
            if (!matchesFilter(i)) continue;
            var imp = is.getImport(i);
            t.row(
                    String.valueOf(i),
                    imp.module(),
                    imp.name(),
                    imp.importType().name().toLowerCase());
        }
        return t;
    }

    private Element renderFunctions() {
        var t =
                table()
                        .header("#", "Name", "Signature")
                        .widths(length(5), fill(1), fill(1))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var fs = module.functionSection();
        var ts = module.typeSection();
        for (int i = 0; i < fs.functionCount(); i++) {
            if (!matchesFilter(i)) continue;
            var typeIdx = fs.getFunctionType(i);
            var ft = ts.getType(typeIdx);
            t.row(
                    String.valueOf(i),
                    functionName(i),
                    ft.params() + " -> " + ft.returns());
        }
        return t;
    }

    private Element renderTables() {
        var t =
                table()
                        .header("#", "Type", "Min", "Max")
                        .widths(length(5), fill(1), length(10), length(10))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var ts = module.tableSection();
        for (int i = 0; i < ts.tableCount(); i++) {
            if (!matchesFilter(i)) continue;
            var tbl = ts.getTable(i);
            t.row(
                    String.valueOf(i),
                    tbl.elementType().toString(),
                    String.valueOf(tbl.limits().min()),
                    tbl.limits().max() < TableLimits.LIMIT_MAX
                            ? String.valueOf(tbl.limits().max())
                            : "unbounded");
        }
        return t;
    }

    private Element renderMemories() {
        var t =
                table()
                        .header("#", "Min (pages)", "Max (pages)")
                        .widths(length(5), fill(1), fill(1))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        module.memorySection()
                .ifPresent(
                        ms -> {
                            for (int i = 0; i < ms.memoryCount(); i++) {
                                if (!matchesFilter(i)) continue;
                                var mem = ms.getMemory(i);
                                t.row(
                                        String.valueOf(i),
                                        String.valueOf(mem.limits().initialPages()),
                                        mem.limits().maximumPages() < MemoryLimits.MAX_PAGES
                                                ? String.valueOf(
                                                        mem.limits().maximumPages())
                                                : "unbounded");
                            }
                        });
        return t;
    }

    private Element renderGlobals() {
        boolean hasInstance = wasmInstance != null;
        int importedGlobals = module.importSection().count(ExternalType.GLOBAL);
        var t =
                hasInstance
                        ? table()
                                .header("#", "Type", "Mutable", "Value")
                                .widths(length(5), fill(1), length(10), fill(1))
                                .columnSpacing(1)
                        : table()
                                .header("#", "Type", "Mutable")
                                .widths(length(5), fill(1), length(10))
                                .columnSpacing(1);
        applyDetailHighlight(t);
        var gs = module.globalSection();
        for (int i = 0; i < gs.globalCount(); i++) {
            if (!matchesFilter(i)) continue;
            var g = gs.getGlobal(i);
            if (hasInstance) {
                var gi = wasmInstance.global(importedGlobals + i);
                var valueStr = formatReturnValue(g.valueType(), gi.getValue());
                t.row(
                        String.valueOf(i),
                        g.valueType().toString(),
                        g.mutabilityType().name().toLowerCase(),
                        valueStr);
            } else {
                t.row(
                        String.valueOf(i),
                        g.valueType().toString(),
                        g.mutabilityType().name().toLowerCase());
            }
        }
        if (inGlobalEdit) {
            var g = gs.getGlobal(globalEditIdx);
            var sb = new StringBuilder();
            sb.append(String.format(
                    "Set global #%d (%s): %s_", globalEditIdx, g.valueType(), globalEditValue));
            if (globalEditError != null) {
                sb.append(String.format("%n%s", globalEditError));
            }
            return column(t, text(sb.toString()).cyan().fit()).fill();
        }
        if (globalEditError != null) {
            return column(t, text(globalEditError).cyan().fit()).fill();
        }
        return t;
    }

    private Element renderExports() {
        var t =
                table()
                        .header("#", "Name", "Kind", "Index")
                        .widths(length(5), fill(1), length(10), length(8))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var es = module.exportSection();
        for (int i = 0; i < es.exportCount(); i++) {
            if (!matchesFilter(i)) continue;
            var exp = es.getExport(i);
            t.row(
                    String.valueOf(i),
                    exp.name(),
                    exp.exportType().name().toLowerCase(),
                    String.valueOf(exp.index()));
        }
        return t;
    }

    private Element renderStart() {
        return module.startSection()
                .map(
                        start -> {
                            long funcIdx = start.startIndex();
                            var importCount = module.importSection().count(ExternalType.FUNCTION);
                            var detail = "Function index: " + funcIdx;
                            if (funcIdx >= importCount) {
                                var localIdx = funcIdx - importCount;
                                var typeIdx =
                                        module.functionSection()
                                                .getFunctionType((int) localIdx);
                                var ft = module.typeSection().getType(typeIdx);
                                detail +=
                                        "\nLocal function #"
                                                + localIdx
                                                + "\nSignature: "
                                                + ft.params()
                                                + " -> "
                                                + ft.returns();
                            }
                            return (Element) richText(detail);
                        })
                .orElse(text("No start section").dim());
    }

    private Element renderElements() {
        var t =
                table()
                        .header("#", "Type", "Init Count")
                        .widths(length(5), fill(1), length(12))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var es = module.elementSection();
        for (int i = 0; i < es.elementCount(); i++) {
            if (!matchesFilter(i)) continue;
            var el = es.getElement(i);
            t.row(
                    String.valueOf(i),
                    el.type().toString(),
                    String.valueOf(el.elementCount()));
        }
        return t;
    }

    private Element renderCode() {
        var t =
                table()
                        .header("#", "Name", "Size")
                        .widths(length(5), fill(1), length(8))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var cs = module.codeSection();
        for (int i = 0; i < cs.functionBodyCount(); i++) {
            if (!matchesFilter(i)) continue;
            var bytes = i < functionBodies.size() ? functionBodies.get(i) : new byte[0];
            t.row(
                    String.valueOf(i),
                    functionName(i),
                    String.valueOf(bytes.length));
        }
        return t;
    }

    private Element renderData() {
        var t =
                table()
                        .header("#", "Kind", "Size (bytes)")
                        .widths(length(5), fill(1), length(14))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var ds = module.dataSection();
        for (int i = 0; i < ds.dataSegmentCount(); i++) {
            if (!matchesFilter(i)) continue;
            var seg = ds.getDataSegment(i);
            var kind = seg.getClass().getSimpleName().replace("DataSegment", "");
            t.row(String.valueOf(i), kind.toLowerCase(), String.valueOf(seg.data().length));
        }
        return t;
    }

    // --- Hex dump formatting ---

    static byte[] encodeTypedValue(String type, String value) {
        return switch (type) {
            case "i32" -> {
                int v = Integer.parseInt(value);
                yield new byte[] {
                    (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)
                };
            }
            case "i64" -> {
                long v = Long.parseLong(value);
                yield new byte[] {
                    (byte) v,
                    (byte) (v >> 8),
                    (byte) (v >> 16),
                    (byte) (v >> 24),
                    (byte) (v >> 32),
                    (byte) (v >> 40),
                    (byte) (v >> 48),
                    (byte) (v >> 56)
                };
            }
            case "f32" -> {
                int bits = Float.floatToIntBits(Float.parseFloat(value));
                yield new byte[] {
                    (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)
                };
            }
            case "f64" -> {
                long bits = Double.doubleToLongBits(Double.parseDouble(value));
                yield new byte[] {
                    (byte) bits,
                    (byte) (bits >> 8),
                    (byte) (bits >> 16),
                    (byte) (bits >> 24),
                    (byte) (bits >> 32),
                    (byte) (bits >> 40),
                    (byte) (bits >> 48),
                    (byte) (bits >> 56)
                };
            }
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
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

    // --- WASM code section byte extraction ---

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

    // --- WAT per-function extraction ---

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
}
