package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

public final class DataView implements View {

    private final int selectedDataIdx;
    private int scrollOffset = 0;
    private final ContentSearchState search = new ContentSearchState();

    DataView(int dataIdx) {
        this.selectedDataIdx = dataIdx;
    }

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) { ctx.navigateTo(new ViewTransition.Quit()); return EventResult.HANDLED; }
        if (search.isActive()) { return search.handleKey(key, scrollTarget(ctx)); }
        if (key.isCancel() || key.isLeft()) { search.reset(); ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
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
                var ds = ctx.module().dataSection();
                if (selectedDataIdx >= ds.dataSegmentCount()) return "";
                return WasmUtils.formatHex(ds.getDataSegment(selectedDataIdx).data());
            }
            @Override public int scrollOffset() { return scrollOffset; }
            @Override public void setScrollOffset(int offset) { scrollOffset = offset; }
        };
    }

    @Override
    public Element render(ViewContext ctx) {
        var ds = ctx.module().dataSection();
        int dataCount = ds.dataSegmentCount();
        var dataTitle = "data #" + selectedDataIdx + " (" + (selectedDataIdx + 1) + "/" + dataCount + ")";
        var seg = ds.getDataSegment(selectedDataIdx);
        var hexContent = renderDataHexView(seg.data());
        var dataPanelTitle = "Hex - " + dataTitle;
        if (!search.query().isEmpty() && !search.isActive()) {
            dataPanelTitle += " [/" + search.query() + "]";
        }
        Element dataHelp;
        if (search.isActive()) {
            dataHelp = row(text(" /: ").cyan().fit(), text(search.query() + "_").yellow().fit(),
                    text("  ESC").cyan().fit(), text(" cancel  ").dim().fit(),
                    text("Enter").cyan().fit(), text(" find").dim().fit());
        } else {
            dataHelp = row(text(" \u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("PgUp/Dn").cyan().fit(), text(" page  ").dim().fit(),
                    text("/").cyan().fit(), text(" search  ").dim().fit(),
                    text("n/N").cyan().fit(), text(" next/prev  ").dim().fit(),
                    text("ESC/\u2190").cyan().fit(), text(" back").dim().fit());
        }

        var contentPanel = panel(() -> hexContent).title(dataPanelTitle).bottomTitle(seg.data().length + " bytes").rounded().borderColor(Color.CYAN).fill(1);
        return ViewLayout.layout(ctx, contentPanel, dataHelp);
    }

    private Element renderDataHexView(byte[] data) {
        if (data.length == 0) return text("No data available").dim();
        var hex = WasmUtils.formatHex(data);
        return richText(WasmUtils.scrollContent(hex, scrollOffset)).overflow(Overflow.CLIP).fill();
    }
}
