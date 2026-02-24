package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.TableLimits;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TableElement;
import dev.tamboui.widgets.table.TableState;

final class SectionRenderers {

    private SectionRenderers() {}

    static Element renderDetail(ViewContext ctx, String sectionName, TableState detailTableState, String searchFilter) {
        return switch (sectionName) {
            case "Types" -> renderTypes(ctx, detailTableState, searchFilter);
            case "Imports" -> renderImports(ctx, detailTableState, searchFilter);
            case "Functions" -> renderFunctions(ctx, detailTableState, searchFilter);
            case "Tables" -> renderTables(ctx, detailTableState, searchFilter);
            case "Memories" -> renderMemories(ctx, detailTableState, searchFilter);
            case "Globals" -> renderGlobals(ctx, detailTableState, searchFilter);
            case "Exports" -> renderExports(ctx, detailTableState, searchFilter);
            case "Start" -> renderStart(ctx);
            case "Elements" -> renderElements(ctx, detailTableState, searchFilter);
            case "Code" -> renderCode(ctx, detailTableState, searchFilter);
            case "Data" -> renderData(ctx, detailTableState, searchFilter);
            default -> text("Select a section").dim();
        };
    }

    static void applyDetailHighlight(TableElement t, TableState state) {
        if (state == null) return;
        t.state(state)
                .highlightSymbol(">> ")
                .highlightStyle(Style.EMPTY.bg(Color.MAGENTA).fg(Color.BLACK));
    }

    static boolean matchesFilter(int index, ViewContext ctx, String searchFilter) {
        if (searchFilter.isEmpty()) return true;
        var filter = searchFilter.toLowerCase();
        if (String.valueOf(index).startsWith(filter)) return true;
        var section = ctx.selectedSectionName();
        var module = ctx.module();
        return switch (section) {
            case "Functions", "Code" -> ctx.functionData().functionName(index).toLowerCase().contains(filter);
            case "Exports" -> {
                var es = module.exportSection();
                yield index < es.exportCount() && es.getExport(index).name().toLowerCase().contains(filter);
            }
            case "Imports" -> {
                var is = module.importSection();
                yield index < is.importCount()
                        && (is.getImport(index).name().toLowerCase().contains(filter)
                                || is.getImport(index).module().toLowerCase().contains(filter));
            }
            default -> false;
        };
    }

    static int detailRowCount(ViewContext ctx) {
        var module = ctx.module();
        return switch (ctx.selectedSectionName()) {
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

    static int filteredDetailRowCount(ViewContext ctx, String searchFilter) {
        int total = detailRowCount(ctx);
        if (searchFilter.isEmpty()) return total;
        int count = 0;
        for (int i = 0; i < total; i++) {
            if (matchesFilter(i, ctx, searchFilter)) count++;
        }
        return count;
    }

    /** Maps a filtered row position back to the original module index. */
    static int filteredToOriginalIndex(int filteredIdx, ViewContext ctx, String searchFilter) {
        if (searchFilter.isEmpty()) return filteredIdx;
        int total = detailRowCount(ctx);
        int count = 0;
        for (int i = 0; i < total; i++) {
            if (matchesFilter(i, ctx, searchFilter)) {
                if (count == filteredIdx) return i;
                count++;
            }
        }
        return filteredIdx;
    }

    private static Element renderTypes(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Params", "Returns").widths(length(5), fill(1), fill(1)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var ts = ctx.module().typeSection();
        for (int i = 0; i < ts.typeCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var ft = ts.getType(i);
            t.row(String.valueOf(i), ft.params().toString(), ft.returns().toString());
        }
        return t;
    }

    private static Element renderImports(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Module", "Name", "Kind").widths(length(5), fill(1), fill(1), length(10)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var is = ctx.module().importSection();
        for (int i = 0; i < is.importCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var imp = is.getImport(i);
            t.row(String.valueOf(i), imp.module(), imp.name(), imp.importType().name().toLowerCase());
        }
        return t;
    }

    private static Element renderFunctions(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Name", "Signature").widths(length(5), fill(1), fill(1)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var module = ctx.module();
        var fs = module.functionSection();
        var ts = module.typeSection();
        for (int i = 0; i < fs.functionCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var typeIdx = fs.getFunctionType(i);
            var ft = ts.getType(typeIdx);
            t.row(String.valueOf(i), ctx.functionData().functionName(i), ft.params() + " -> " + ft.returns());
        }
        return t;
    }

    private static Element renderTables(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Type", "Min", "Max").widths(length(5), fill(1), length(10), length(10)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var ts = ctx.module().tableSection();
        for (int i = 0; i < ts.tableCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var tbl = ts.getTable(i);
            t.row(String.valueOf(i), tbl.elementType().toString(), String.valueOf(tbl.limits().min()),
                    tbl.limits().max() < TableLimits.LIMIT_MAX ? String.valueOf(tbl.limits().max()) : "unbounded");
        }
        return t;
    }

    private static Element renderMemories(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Min (pages)", "Max (pages)").widths(length(5), fill(1), fill(1)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        ctx.module().memorySection().ifPresent(ms -> {
            for (int i = 0; i < ms.memoryCount(); i++) {
                if (!matchesFilter(i, ctx, searchFilter)) continue;
                var mem = ms.getMemory(i);
                t.row(String.valueOf(i), String.valueOf(mem.limits().initialPages()),
                        mem.limits().maximumPages() < MemoryLimits.MAX_PAGES ? String.valueOf(mem.limits().maximumPages()) : "unbounded");
            }
        });
        return t;
    }

    private static Element renderGlobals(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var module = ctx.module();
        boolean hasInstance = ctx.instanceManager().instance() != null;
        int importedGlobals = module.importSection().count(ExternalType.GLOBAL);
        var t = hasInstance
                ? table().header("#", "Type", "Mutable", "Value").widths(length(5), fill(1), length(10), fill(1)).columnSpacing(1)
                : table().header("#", "Type", "Mutable").widths(length(5), fill(1), length(10)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var gs = module.globalSection();
        for (int i = 0; i < gs.globalCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var g = gs.getGlobal(i);
            if (hasInstance) {
                var gi = ctx.instanceManager().instance().global(importedGlobals + i);
                var valueStr = ParamUtils.formatReturnValue(g.valueType(), gi.getValue());
                t.row(String.valueOf(i), g.valueType().toString(), g.mutabilityType().name().toLowerCase(), valueStr);
            } else {
                t.row(String.valueOf(i), g.valueType().toString(), g.mutabilityType().name().toLowerCase());
            }
        }
        return t;
    }

    private static Element renderExports(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Name", "Kind", "Index").widths(length(5), fill(1), length(10), length(8)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var es = ctx.module().exportSection();
        for (int i = 0; i < es.exportCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var exp = es.getExport(i);
            t.row(String.valueOf(i), exp.name(), exp.exportType().name().toLowerCase(), String.valueOf(exp.index()));
        }
        return t;
    }

    private static Element renderStart(ViewContext ctx) {
        var module = ctx.module();
        return module.startSection()
                .map(start -> {
                    long funcIdx = start.startIndex();
                    var importCount = module.importSection().count(ExternalType.FUNCTION);
                    var detail = "Function index: " + funcIdx;
                    if (funcIdx >= importCount) {
                        var localIdx = funcIdx - importCount;
                        var typeIdx = module.functionSection().getFunctionType((int) localIdx);
                        var ft = module.typeSection().getType(typeIdx);
                        detail += "\nLocal function #" + localIdx + "\nSignature: " + ft.params() + " -> " + ft.returns();
                    }
                    return (Element) richText(detail);
                })
                .orElse(text("No start section").dim());
    }

    private static Element renderElements(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Type", "Init Count").widths(length(5), fill(1), length(12)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var es = ctx.module().elementSection();
        for (int i = 0; i < es.elementCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var el = es.getElement(i);
            t.row(String.valueOf(i), el.type().toString(), String.valueOf(el.elementCount()));
        }
        return t;
    }

    private static Element renderCode(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Name", "Size").widths(length(5), fill(1), length(8)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var cs = ctx.module().codeSection();
        var bodies = ctx.functionData().functionBodies();
        for (int i = 0; i < cs.functionBodyCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var bytes = i < bodies.size() ? bodies.get(i) : new byte[0];
            t.row(String.valueOf(i), ctx.functionData().functionName(i), String.valueOf(bytes.length));
        }
        return t;
    }

    private static Element renderData(ViewContext ctx, TableState detailTableState, String searchFilter) {
        var t = table().header("#", "Kind", "Size (bytes)").widths(length(5), fill(1), length(14)).columnSpacing(1);
        applyDetailHighlight(t, detailTableState);
        var ds = ctx.module().dataSection();
        for (int i = 0; i < ds.dataSegmentCount(); i++) {
            if (!matchesFilter(i, ctx, searchFilter)) continue;
            var seg = ds.getDataSegment(i);
            var kind = seg.getClass().getSimpleName().replace("DataSegment", "");
            t.row(String.valueOf(i), kind.toLowerCase(), String.valueOf(seg.data().length));
        }
        return t;
    }
}
