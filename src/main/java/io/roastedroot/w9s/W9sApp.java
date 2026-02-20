package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.TableLimits;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;
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

    // Navigation state for detail pane
    private final TableState detailTableState = new TableState();
    private boolean inDetailView = false;

    // Search/filter state
    private boolean inSearch = false;
    private String searchFilter = "";

    // Navigation state for function detail view
    private boolean inFunctionView = false;
    private int selectedFunctionIdx = 0;
    private boolean showWatMode = false; // false = hex, true = WAT
    private int functionViewScrollOffset = 0;

    // Navigation state for data hex dump view
    private boolean inDataView = false;
    private int selectedDataIdx = 0;
    private int dataViewScrollOffset = 0;

    public W9sApp(String filename, WasmModule module, byte[] wasmBytes) {
        this.filename = filename;
        this.module = module;
        this.sectionRows = buildSectionRows(module);
        tableState.select(0);

        this.functionBodies = extractFunctionBodies(wasmBytes);

        this.functionWatsFuture =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return extractFunctions(Wasm2Wat.print(wasmBytes));
                            } catch (Exception e) {
                                return List.of();
                            }
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
        }
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

    private EventResult handleKey(KeyEvent key, ToolkitRunner runner) {
        if (key.isQuit()) {
            runner.quit();
            return EventResult.HANDLED;
        }

        if (inFunctionView) {
            if (key.isCancel() || key.isLeft()) {
                inFunctionView = false;
                return EventResult.HANDLED;
            }
            if (key.isSelect() || key.isConfirm()) {
                showWatMode = !showWatMode;
                functionViewScrollOffset = 0;
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
            if (key.isCancel() || key.isLeft()) {
                inDataView = false;
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
                    functionViewScrollOffset = 0;
                    inDetailView = false;
                    searchFilter = "";
                    return EventResult.HANDLED;
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
                                functionViewScrollOffset = 0;
                                inDetailView = false;
                                searchFilter = "";
                                return EventResult.HANDLED;
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
                }
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
                                text(" \u2588\u2584\u2588 \u2588\u2580\u2588 \u2588\u2580\u2580")
                                        .fg(Color.LIGHT_CYAN)
                                        .bold(),
                                text(" \u2588\u2580\u2588 \u2580\u2580\u2588 \u2580\u2580\u2588")
                                        .cyan()
                                        .bold(),
                                text(" \u2580 \u2580 \u2580\u2580\u2580 \u2580\u2580\u2580")
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
            var funcTitle =
                    "func #" + selectedFunctionIdx + " (" + (selectedFunctionIdx + 1) + "/"
                            + funcCount + ")";
            var modeLabel = showWatMode ? "WAT" : "Hex";
            var modeBorder = showWatMode ? Color.MAGENTA : Color.CYAN;
            var contentView = showWatMode
                    ? renderWatView(selectedFunctionIdx)
                    : renderHexView(selectedFunctionIdx);
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
                                            .title(modeLabel + " - " + funcTitle)
                                            .bottomTitle(functionSignature(selectedFunctionIdx))
                                            .rounded()
                                            .borderColor(modeBorder)
                                            .fill(1))
                                    .fill(),
                            panel(row(
                                    text(" q").cyan().fit(),
                                    text(" quit  ").dim().fit(),
                                    text("\u2191\u2193").cyan().fit(),
                                    text(" scroll  ").dim().fit(),
                                    text("Enter").cyan().fit(),
                                    text(" hex/WAT  ").dim().fit(),
                                    text("Home/End").cyan().fit(),
                                    text(" top/bottom  ").dim().fit(),
                                    text("ESC/\u2190").cyan().fit(),
                                    text(" back").dim().fit()))
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
                                            .title("Hex - " + dataTitle)
                                            .bottomTitle(seg.data().length + " bytes")
                                            .rounded()
                                            .borderColor(Color.CYAN)
                                            .fill(1))
                                    .fill(),
                            panel(row(
                                    text(" q").cyan().fit(),
                                    text(" quit  ").dim().fit(),
                                    text("\u2191\u2193").cyan().fit(),
                                    text(" scroll  ").dim().fit(),
                                    text("Home/End").cyan().fit(),
                                    text(" top/bottom  ").dim().fit(),
                                    text("ESC/\u2190").cyan().fit(),
                                    text(" back").dim().fit()))
                                    .rounded()
                                    .borderColor(Color.DARK_GRAY)
                                    .length(3))
                    .fill();
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
            } else if ("Exports".equals(selectedName)) {
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
                        .header("#", "Type Idx", "Signature")
                        .widths(length(5), length(10), fill(1))
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
                    String.valueOf(typeIdx),
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
        var t =
                table()
                        .header("#", "Type", "Mutable")
                        .widths(length(5), fill(1), length(10))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var gs = module.globalSection();
        for (int i = 0; i < gs.globalCount(); i++) {
            if (!matchesFilter(i)) continue;
            var g = gs.getGlobal(i);
            t.row(
                    String.valueOf(i),
                    g.valueType().toString(),
                    g.mutabilityType().name().toLowerCase());
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
                        .header("#", "Size", "Bytes")
                        .widths(length(5), length(8), fill(1))
                        .columnSpacing(1);
        applyDetailHighlight(t);
        var cs = module.codeSection();
        for (int i = 0; i < cs.functionBodyCount(); i++) {
            if (!matchesFilter(i)) continue;
            var bytes = i < functionBodies.size() ? functionBodies.get(i) : new byte[0];
            var preview = formatHexPreview(bytes, 16);
            t.row(
                    String.valueOf(i),
                    String.valueOf(bytes.length),
                    preview);
        }
        return t;
    }

    static String formatHexPreview(byte[] data, int maxBytes) {
        var sb = new StringBuilder();
        int len = Math.min(data.length, maxBytes);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        if (data.length > maxBytes) {
            sb.append(" ...");
        }
        return sb.toString();
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
