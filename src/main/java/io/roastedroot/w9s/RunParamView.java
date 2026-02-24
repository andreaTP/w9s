package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.wasm.types.ValType;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RunParamView implements View {

    private final String exportName;
    private final List<ValType> paramTypes;
    private final List<ValType> returnTypes;
    private final String[] paramValues;
    private int focusIdx = 0;
    private String paramError;

    RunParamView(String exportName, List<ValType> paramTypes, List<ValType> returnTypes) {
        this.exportName = exportName;
        this.paramTypes = paramTypes;
        this.returnTypes = returnTypes;
        this.paramValues = new String[paramTypes.size()];
        Arrays.fill(paramValues, "");
    }

    String exportName() { return exportName; }
    List<ValType> paramTypes() { return paramTypes; }
    List<ValType> returnTypes() { return returnTypes; }
    String[] paramValues() { return paramValues; }

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) { ctx.navigateTo(new ViewTransition.Quit()); return EventResult.HANDLED; }
        if (key.isCancel()) { ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
        if (key.isConfirm()) {
            long[] args = new long[paramTypes.size()];
            for (int i = 0; i < paramTypes.size(); i++) {
                try {
                    args[i] = ParamUtils.parseParam(paramTypes.get(i), paramValues[i]);
                } catch (Exception e) {
                    paramError = "Invalid param " + i + ": " + e.getMessage();
                    return EventResult.HANDLED;
                }
            }
            paramError = null;
            RunOutputView.executeAndTransition(ctx, exportName, paramTypes, returnTypes, paramValues, args);
            return EventResult.HANDLED;
        }
        if (key.isUp()) { if (focusIdx > 0) focusIdx--; return EventResult.HANDLED; }
        if (key.isDown()) { if (focusIdx < paramTypes.size() - 1) focusIdx++; return EventResult.HANDLED; }
        if (key.isDeleteBackward()) {
            if (!paramValues[focusIdx].isEmpty()) {
                paramValues[focusIdx] = paramValues[focusIdx].substring(0, paramValues[focusIdx].length() - 1);
            }
            paramError = null; return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            paramValues[focusIdx] += key.character();
            paramError = null; return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    @Override
    public Element render(ViewContext ctx) {
        var lines = new ArrayList<Line>();
        lines.add(Line.from(List.of(
                Span.styled("Signature: ", Style.EMPTY.dim()),
                Span.styled(paramTypes + " -> " + returnTypes, Style.EMPTY))));
        lines.add(Line.empty());
        for (int i = 0; i < paramTypes.size(); i++) {
            var focused = (i == focusIdx);
            var prefix = focused ? "\u25b6 " : "  ";
            var cursor = focused ? "_" : "";
            var label = prefix + "param " + i + " (" + paramTypes.get(i) + "): ";
            var value = paramValues[i] + cursor;
            if (focused) {
                lines.add(Line.from(List.of(Span.styled(label, Style.EMPTY.fg(Color.YELLOW).bold()), Span.styled(value, Style.EMPTY.fg(Color.YELLOW).bold()))));
            } else {
                lines.add(Line.from(List.of(Span.styled(label, Style.EMPTY.dim()), Span.styled(value, Style.EMPTY))));
            }
        }
        if (paramError != null) {
            lines.add(Line.empty());
            lines.add(Line.from(List.of(Span.styled("  Error: " + paramError, Style.EMPTY.fg(Color.YELLOW)))));
        }
        var styledText = Text.from(lines);
        var helpContent = row(text(" \u2191\u2193").cyan().fit(), text(" navigate  ").dim().fit(),
                text("Enter").cyan().fit(), text(" execute  ").dim().fit(),
                text("ESC").cyan().fit(), text(" cancel").dim().fit());

        var contentPanel = panel(richText(styledText).overflow(Overflow.CLIP).fill()).title("Run: " + exportName).rounded().borderColor(Color.CYAN).fill(1);
        return ViewLayout.layout(ctx, contentPanel, helpContent);
    }
}
