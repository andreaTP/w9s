package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.MutabilityType;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;

public final class DetailView implements View {

    private final TableState detailTableState = new TableState();
    private boolean inSearch = false;
    private String searchFilter = "";
    private boolean inGlobalEdit = false;
    private int globalEditIdx = -1;
    private String globalEditValue = "";
    private String globalEditError;

    public DetailView() {
        detailTableState.select(0);
    }

    TableState detailTableState() {
        return detailTableState;
    }

    void resetFilter() {
        searchFilter = "";
        inSearch = false;
        detailTableState.select(0);
    }

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) {
            ctx.navigateTo(new ViewTransition.Quit());
            return EventResult.HANDLED;
        }
        var module = ctx.module();

        // Search input mode
        if (inSearch) {
            if (key.isCancel()) { inSearch = false; searchFilter = ""; detailTableState.select(0); return EventResult.HANDLED; }
            if (key.isConfirm()) { inSearch = false; return EventResult.HANDLED; }
            if (key.isDeleteBackward() && !searchFilter.isEmpty()) {
                searchFilter = searchFilter.substring(0, searchFilter.length() - 1);
                detailTableState.select(0); return EventResult.HANDLED;
            }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                searchFilter += key.character(); detailTableState.select(0); return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        // Sub-mode: global edit
        if (inGlobalEdit) {
            if (key.isCancel()) { inGlobalEdit = false; globalEditError = null; return EventResult.HANDLED; }
            if (key.isConfirm()) {
                if (!globalEditValue.isEmpty()) {
                    try {
                        var gs = module.globalSection();
                        int importedGlobals = module.importSection().count(ExternalType.GLOBAL);
                        var g = gs.getGlobal(globalEditIdx);
                        var gi = ctx.instanceManager().instance().global(importedGlobals + globalEditIdx);
                        long raw = ParamUtils.parseParam(g.valueType(), globalEditValue);
                        gi.setValue(raw);
                        globalEditError = null;
                    } catch (Exception e) {
                        globalEditError = "Set failed: " + e.getMessage();
                    }
                }
                inGlobalEdit = false; return EventResult.HANDLED;
            }
            if (key.isDeleteBackward() && !globalEditValue.isEmpty()) {
                globalEditValue = globalEditValue.substring(0, globalEditValue.length() - 1);
                return EventResult.HANDLED;
            }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
                globalEditValue += key.character(); return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        if (key.isChar('/')) { inSearch = true; searchFilter = ""; detailTableState.select(0); return EventResult.HANDLED; }
        if (key.isCancel() || key.isLeft()) {
            searchFilter = "";
            ctx.navigateTo(new ViewTransition.ToSectionNav());
            return EventResult.HANDLED;
        }
        if (key.isUp()) { detailTableState.selectPrevious(); return EventResult.HANDLED; }
        if (key.isDown()) { detailTableState.selectNext(SectionRenderers.filteredDetailRowCount(ctx, searchFilter)); return EventResult.HANDLED; }
        if (key.isPageUp()) {
            int current = detailTableState.selected() != null ? detailTableState.selected() : 0;
            detailTableState.select(Math.max(0, current - ViewLayout.PAGE_SIZE)); return EventResult.HANDLED;
        }
        if (key.isPageDown()) {
            int current = detailTableState.selected() != null ? detailTableState.selected() : 0;
            int count = SectionRenderers.filteredDetailRowCount(ctx, searchFilter);
            detailTableState.select(Math.min(count - 1, current + ViewLayout.PAGE_SIZE)); return EventResult.HANDLED;
        }
        if (key.isHome()) { detailTableState.selectFirst(); return EventResult.HANDLED; }
        if (key.isEnd()) { detailTableState.selectLast(SectionRenderers.filteredDetailRowCount(ctx, searchFilter)); return EventResult.HANDLED; }

        if (key.isSelect() || key.isConfirm()) {
            var section = ctx.selectedSectionName();
            var detailIdx = originalIndex(ctx);
            if ("Functions".equals(section)) {
                int codeIdx = ctx.sectionIndex("Code");
                if (codeIdx >= 0 && detailIdx < module.codeSection().functionBodyCount()) {
                    ctx.sectionTableState().select(codeIdx);
                    detailTableState.select(detailIdx);
                    searchFilter = "";
                    return EventResult.HANDLED;
                }
            } else if ("Code".equals(section) && module.codeSection().functionBodyCount() > 0) {
                searchFilter = "";
                ctx.navigateTo(new ViewTransition.ToFunctionView(detailIdx));
                return EventResult.HANDLED;
            } else if ("Imports".equals(section)) {
                var is = module.importSection();
                if (detailIdx < is.importCount()) {
                    var imp = is.getImport(detailIdx);
                    if (imp instanceof FunctionImport funcImport) {
                        int typesIdx = ctx.sectionIndex("Types");
                        if (typesIdx >= 0) {
                            ctx.sectionTableState().select(typesIdx);
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
                        int importedFuncs = module.importSection().count(ExternalType.FUNCTION);
                        int localIdx = (int) exp.index() - importedFuncs;
                        if (localIdx >= 0 && localIdx < module.codeSection().functionBodyCount()) {
                            searchFilter = "";
                            ctx.navigateTo(new ViewTransition.ToFunctionView(localIdx));
                            return EventResult.HANDLED;
                        }
                    } else if (exp.exportType() == ExternalType.MEMORY) {
                        if (ctx.instanceManager().ensureInstance()) {
                            searchFilter = "";
                            ctx.navigateTo(new ViewTransition.ToMemoryView());
                            return EventResult.HANDLED;
                        }
                    }
                }
            } else if ("Data".equals(section)) {
                var ds = module.dataSection();
                if (detailIdx < ds.dataSegmentCount()) {
                    searchFilter = "";
                    ctx.navigateTo(new ViewTransition.ToDataView(detailIdx));
                    return EventResult.HANDLED;
                }
            } else if ("Memories".equals(section)) {
                if (ctx.instanceManager().ensureInstance()) {
                    searchFilter = "";
                    ctx.navigateTo(new ViewTransition.ToMemoryView());
                    return EventResult.HANDLED;
                }
            }
        }

        // 'e' in Globals detail
        if (key.isChar('e') && "Globals".equals(ctx.selectedSectionName())) {
            var detailIdx = originalIndex(ctx);
            var gs = module.globalSection();
            if (detailIdx < gs.globalCount() && gs.getGlobal(detailIdx).mutabilityType() == MutabilityType.Var) {
                if (ctx.instanceManager().ensureInstance()) {
                    inGlobalEdit = true; globalEditIdx = detailIdx; globalEditValue = ""; globalEditError = null;
                } else {
                    globalEditError = ctx.instanceManager().instanceError();
                }
                return EventResult.HANDLED;
            }
        }
        // 'r' in Exports detail
        if (key.isChar('r') && "Exports".equals(ctx.selectedSectionName())) {
            handleRunExport(ctx);
            return EventResult.HANDLED;
        }
        // 'f' in Code detail
        if (key.isChar('f') && "Code".equals(ctx.selectedSectionName())) {
            var detailIdx = originalIndex(ctx);
            int funcIdx = ctx.sectionIndex("Functions");
            if (funcIdx >= 0 && detailIdx < module.functionSection().functionCount()) {
                ctx.sectionTableState().select(funcIdx);
                detailTableState.select(detailIdx);
                searchFilter = "";
                return EventResult.HANDLED;
            }
        }
        // 'c' in Functions detail
        if (key.isChar('c') && "Functions".equals(ctx.selectedSectionName())) {
            var detailIdx = originalIndex(ctx);
            int codeIdx = ctx.sectionIndex("Code");
            if (codeIdx >= 0 && detailIdx < module.codeSection().functionBodyCount()) {
                ctx.sectionTableState().select(codeIdx);
                detailTableState.select(detailIdx);
                searchFilter = "";
                return EventResult.HANDLED;
            }
        }
        return EventResult.UNHANDLED;
    }

    private int originalIndex(ViewContext ctx) {
        int selected = detailTableState.selected() != null ? detailTableState.selected() : 0;
        return SectionRenderers.filteredToOriginalIndex(selected, ctx, searchFilter);
    }

    private void handleRunExport(ViewContext ctx) {
        var module = ctx.module();
        var detailIdx = originalIndex(ctx);
        var es = module.exportSection();
        if (detailIdx >= es.exportCount()) return;
        var exp = es.getExport(detailIdx);
        if (exp.exportType() != ExternalType.FUNCTION) return;
        ctx.navigateTo(new ViewTransition.ToRunParamView(exp.name()));
    }

    @Override
    public Element render(ViewContext ctx) {
        var selectedName = ctx.selectedSectionName();
        Element helpContent;
        if (inSearch) {
            helpContent = row(
                    text(" /: ").cyan().fit(),
                    text(searchFilter + "_").yellow().fit(),
                    text("  ESC").cyan().fit(),
                    text(" cancel  ").dim().fit(),
                    text("Enter").cyan().fit(),
                    text(" confirm").dim().fit());
        } else if ("Code".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" view func").dim().fit());
        } else if ("Functions".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" \u2192 Code").dim().fit());
        } else if ("Imports".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" \u2192 Type").dim().fit());
        } else if ("Exports".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("r").cyan().fit(), text(" run  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" view/edit").dim().fit());
        } else if ("Data".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" view hex").dim().fit());
        } else if ("Globals".equals(selectedName)) {
            if (inGlobalEdit) {
                helpContent = row(text(" Enter").cyan().fit(), text(" confirm  ").dim().fit(),
                        text("ESC").cyan().fit(), text(" cancel").dim().fit());
            } else {
                helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                        text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                        text("/").cyan().fit(), text(" filter  ").dim().fit(),
                        text("e").cyan().fit(), text(" edit").dim().fit());
            }
        } else if ("Memories".equals(selectedName)) {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" view/edit").dim().fit());
        } else {
            helpContent = row(text(" ESC/\u2190").cyan().fit(), text(" back  ").dim().fit(),
                    text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("/").cyan().fit(), text(" filter").dim().fit());
        }

        var detailTitle = selectedName;
        if (!searchFilter.isEmpty() && !inSearch) {
            detailTitle = selectedName + " [/" + searchFilter + "]";
        }

        var detailContent = renderDetailWithGlobalEdit(ctx, selectedName);
        var contentPanel = panel(() -> detailContent)
                .title(detailTitle)
                .rounded()
                .borderColor(Color.MAGENTA)
                .fill(1);

        return ViewLayout.layout(ctx, contentPanel, helpContent);
    }

    private Element renderDetailWithGlobalEdit(ViewContext ctx, String selectedName) {
        var base = SectionRenderers.renderDetail(ctx, selectedName, detailTableState, searchFilter);
        if ("Globals".equals(selectedName)) {
            if (inGlobalEdit) {
                var gs = ctx.module().globalSection();
                var g = gs.getGlobal(globalEditIdx);
                var sb = new StringBuilder();
                sb.append(String.format("Set global #%d (%s): %s_", globalEditIdx, g.valueType(), globalEditValue));
                if (globalEditError != null) sb.append(String.format("%n%s", globalEditError));
                return column(base, text(sb.toString()).yellow().fit()).fill();
            }
            if (globalEditError != null) return column(base, text(globalEditError).yellow().fit()).fill();
        }
        return base;
    }
}
