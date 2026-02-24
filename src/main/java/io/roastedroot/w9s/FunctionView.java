package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

public final class FunctionView implements View {

    private int selectedFunctionIdx;
    private boolean showWatMode = true;
    private int scrollOffset = 0;
    private final ContentSearchState search = new ContentSearchState();

    FunctionView(int funcIdx) {
        this.selectedFunctionIdx = funcIdx;
    }

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) { ctx.navigateTo(new ViewTransition.Quit()); return EventResult.HANDLED; }
        if (search.isActive()) {
            return search.handleKey(key, scrollTarget(ctx));
        }
        if (key.isCancel() || key.isLeft()) {
            search.reset();
            ctx.navigateTo(new ViewTransition.ToDetailView());
            return EventResult.HANDLED;
        }
        if (key.isSelect() || key.isConfirm()) {
            showWatMode = !showWatMode; scrollOffset = 0; search.reset(); return EventResult.HANDLED;
        }
        if (key.isChar('/')) { search.startSearch(); return EventResult.HANDLED; }
        if (key.isChar('n') && !search.query().isEmpty()) { search.searchNext(scrollTarget(ctx)); return EventResult.HANDLED; }
        if (key.isChar('N') && !search.query().isEmpty()) { search.searchPrev(scrollTarget(ctx)); return EventResult.HANDLED; }
        int newOffset = ScrollHandler.handleKey(key, scrollOffset);
        if (newOffset >= 0) { scrollOffset = newOffset; return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    private ContentSearchState.ScrollTarget scrollTarget(ViewContext ctx) {
        return new ContentSearchState.ScrollTarget() {
            @Override public String contentText() {
                if (showWatMode) {
                    if (!ctx.functionData().functionWatsFuture().isDone()) return "";
                    var wats = ctx.functionData().functionWatsFuture().join();
                    if (selectedFunctionIdx >= wats.size()) return "";
                    return WasmUtils.formatWat(wats.get(selectedFunctionIdx));
                } else {
                    if (selectedFunctionIdx >= ctx.functionData().functionBodies().size()) return "";
                    return WasmUtils.formatHex(ctx.functionData().functionBodies().get(selectedFunctionIdx));
                }
            }
            @Override public int scrollOffset() { return scrollOffset; }
            @Override public void setScrollOffset(int offset) { scrollOffset = offset; }
        };
    }

    @Override
    public Element render(ViewContext ctx) {
        var module = ctx.module();
        int funcCount = module.codeSection().functionBodyCount();
        var funcName = ctx.functionData().functionName(selectedFunctionIdx);
        var funcTitle = funcName + " (" + (selectedFunctionIdx + 1) + "/" + funcCount + ")";
        var modeLabel = showWatMode ? "WAT" : "Hex";
        var modeBorder = showWatMode ? Color.MAGENTA : Color.CYAN;
        var contentView = showWatMode ? renderWatView(ctx) : renderHexView(ctx);
        var panelTitle = modeLabel + " - " + funcTitle;
        if (!search.query().isEmpty() && !search.isActive()) {
            panelTitle += " [/" + search.query() + "]";
        }
        Element funcHelp;
        if (search.isActive()) {
            funcHelp = row(text(" /: ").cyan().fit(), text(search.query() + "_").yellow().fit(),
                    text("  ESC").cyan().fit(), text(" cancel  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" find").dim().fit());
        } else {
            funcHelp = row(text(" \u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("PgUp/Dn").cyan().fit(), text(" page  ").dim().fit(),
                    text("/").cyan().fit(), text(" search  ").dim().fit(),
                    text("n/N").cyan().fit(), text(" next/prev  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" hex/WAT  ").dim().fit(),
                    text("ESC/\u2190").cyan().fit(), text(" back").dim().fit());
        }

        var contentPanel = panel(() -> contentView).title(panelTitle).bottomTitle(functionSignature(ctx)).rounded().borderColor(modeBorder).fill(1);
        return ViewLayout.layout(ctx, contentPanel, funcHelp);
    }

    private Element renderHexView(ViewContext ctx) {
        if (selectedFunctionIdx >= ctx.functionData().functionBodies().size()) return text("No hex data available").dim();
        var hex = WasmUtils.formatHex(ctx.functionData().functionBodies().get(selectedFunctionIdx));
        return richText(WasmUtils.scrollContent(hex, scrollOffset)).overflow(Overflow.CLIP).fill();
    }

    private Element renderWatView(ViewContext ctx) {
        if (!ctx.functionData().functionWatsFuture().isDone()) return text("Loading WAT...").dim();
        var wats = ctx.functionData().functionWatsFuture().join();
        if (selectedFunctionIdx >= wats.size()) return text("No WAT data available").dim();
        var cached = ctx.functionData().highlightedWatCache().get(selectedFunctionIdx);
        if (cached != null) {
            var scrolled = WasmUtils.scrollAnsiContent(cached, scrollOffset);
            return richText(AnsiTextParser.parseAnsiText(scrolled)).overflow(Overflow.CLIP).fill();
        }
        if (ctx.functionData().highlighterReady().isDone() && !ctx.functionData().highlighterReady().isCompletedExceptionally()) {
            var rawWat = wats.get(selectedFunctionIdx);
            var result = ctx.functionData().watHighlighter().highlight(rawWat);
            if (result.success()) {
                var numbered = WasmUtils.addLineNumbers(result.string());
                ctx.functionData().highlightedWatCache().put(selectedFunctionIdx, numbered);
                var scrolled = WasmUtils.scrollAnsiContent(numbered, scrollOffset);
                return richText(AnsiTextParser.parseAnsiText(scrolled)).overflow(Overflow.CLIP).fill();
            }
        }
        var wat = WasmUtils.formatWat(wats.get(selectedFunctionIdx));
        return richText(WasmUtils.scrollContent(wat, scrollOffset)).overflow(Overflow.CLIP).fill();
    }

    private String functionSignature(ViewContext ctx) {
        var module = ctx.module();
        if (selectedFunctionIdx < module.functionSection().functionCount()) {
            var typeIdx = module.functionSection().getFunctionType(selectedFunctionIdx);
            var ft = module.typeSection().getType(typeIdx);
            return ft.params() + " \u2192 " + ft.returns();
        }
        return "";
    }
}
