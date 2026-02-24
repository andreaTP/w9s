package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

public final class SectionNavView implements View {

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit() || key.isCancel()) {
            ctx.navigateTo(new ViewTransition.Quit());
            return EventResult.HANDLED;
        }
        var ts = ctx.sectionTableState();
        if (key.isUp()) { ts.selectPrevious(); return EventResult.HANDLED; }
        if (key.isDown()) { ts.selectNext(ctx.sectionRows().size()); return EventResult.HANDLED; }
        if (key.isHome()) { ts.selectFirst(); return EventResult.HANDLED; }
        if (key.isEnd()) { ts.selectLast(ctx.sectionRows().size()); return EventResult.HANDLED; }
        if (key.isRight()) {
            int count = SectionRenderers.detailRowCount(ctx);
            if (count > 0) { ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
        }
        if (key.isSelect() || key.isConfirm()) {
            if ("Code".equals(ctx.selectedSectionName()) && ctx.module().codeSection().functionBodyCount() > 0) {
                ctx.navigateTo(new ViewTransition.ToFunctionView(0));
                return EventResult.HANDLED;
            }
        }
        return EventResult.UNHANDLED;
    }

    @Override
    public Element render(ViewContext ctx) {
        var helpContent = row(
                text(" q/ESC").cyan().fit(),
                text(" quit  ").dim().fit(),
                text("\u2191\u2193").cyan().fit(),
                text(" navigate  ").dim().fit(),
                text("\u2192").cyan().fit(),
                text(" detail").dim().fit());
        var selectedName = ctx.selectedSectionName();

        var contentPanel = panel(() -> SectionRenderers.renderDetail(ctx, selectedName, null, ""))
                .title(selectedName)
                .rounded()
                .borderColor(Color.DARK_GRAY)
                .fill(1);

        return ViewLayout.layout(ctx, contentPanel, helpContent, Color.GREEN);
    }
}
