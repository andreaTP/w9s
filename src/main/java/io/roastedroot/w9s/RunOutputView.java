package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasm.types.ValType;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RunOutputView implements View {

    private final String exportName;
    private final List<ValType> paramTypes;
    private final List<ValType> returnTypes;
    private final String[] paramValues;
    private long[] results;
    private String stdout = "";
    private String stderr = "";
    private String execError;
    private int exitCode = -1;
    private long durationMs;
    private int scrollOffset = 0;

    RunOutputView(String exportName, List<ValType> paramTypes, List<ValType> returnTypes, String[] paramValues) {
        this.exportName = exportName;
        this.paramTypes = paramTypes;
        this.returnTypes = returnTypes;
        this.paramValues = paramValues;
    }

    void setError(String error) {
        this.execError = error;
    }

    static void executeAndTransition(ViewContext ctx, String exportName, List<ValType> paramTypes,
            List<ValType> returnTypes, String[] paramValues, long[] args) {
        var view = new RunOutputView(exportName, paramTypes, returnTypes, paramValues);
        view.execute(ctx, args);
        ctx.navigateTo(new ViewTransition.ToRunOutputView(exportName));
        // Store the view so coordinator can pick it up
        ctx.setPendingRunOutputView(view);
    }

    void execute(ViewContext ctx, long[] args) {
        execError = null; results = null; exitCode = -1; stdout = ""; stderr = "";
        var stdoutCapture = ctx.instanceManager().stdoutCapture();
        var stderrCapture = ctx.instanceManager().stderrCapture();
        if (stdoutCapture != null) stdoutCapture.reset();
        if (stderrCapture != null) stderrCapture.reset();
        long startTime = System.currentTimeMillis();
        try {
            ExportFunction func = ctx.instanceManager().instance().export(exportName);
            results = func.apply(args);
        } catch (WasiExitException e) {
            exitCode = e.exitCode();
            ctx.instanceManager().requestReset();
        } catch (TrapException e) {
            execError = "Trap: " + e.getMessage();
        } catch (Exception e) {
            execError = "Error: " + e.getMessage();
        }
        durationMs = System.currentTimeMillis() - startTime;
        if (stdoutCapture != null && stdoutCapture.size() > 0) {
            stdout = stdoutCapture.toString(StandardCharsets.UTF_8);
        }
        if (stderrCapture != null && stderrCapture.size() > 0) {
            stderr = stderrCapture.toString(StandardCharsets.UTF_8);
        }
        scrollOffset = 0;
    }

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) { ctx.navigateTo(new ViewTransition.Quit()); return EventResult.HANDLED; }
        if (key.isCancel() || key.isLeft()) { ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
        if (key.isChar('r')) {
            if (ctx.instanceManager().ensureInstance()) {
                long[] args = new long[0];
                if (paramValues != null && paramValues.length > 0) {
                    args = new long[paramValues.length];
                    for (int i = 0; i < paramValues.length; i++) {
                        try { args[i] = ParamUtils.parseParam(paramTypes.get(i), paramValues[i]); }
                        catch (Exception e) { return EventResult.HANDLED; }
                    }
                }
                execute(ctx, args);
            }
            return EventResult.HANDLED;
        }
        if (key.isChar('R')) { ctx.instanceManager().requestReset(); ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
        int newOffset = ScrollHandler.handleKey(key, scrollOffset);
        if (newOffset >= 0) { scrollOffset = newOffset; return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    @Override
    public Element render(ViewContext ctx) {
        boolean hasError = execError != null;
        boolean hasWasiExit = exitCode >= 0;
        var borderColor = (hasError || (hasWasiExit && exitCode != 0)) ? Color.RED : Color.GREEN;

        var lines = new ArrayList<Line>();

        // Input Parameters section
        if (paramValues != null && paramValues.length > 0) {
            lines.add(Line.from(List.of(
                    Span.styled("━━ Input Parameters ━━", Style.EMPTY.fg(Color.CYAN).bold()))));
            for (int i = 0; i < paramValues.length; i++) {
                lines.add(Line.from(List.of(
                        Span.styled("  param " + i + " (" + paramTypes.get(i) + "): ", Style.EMPTY.dim()),
                        Span.styled(paramValues[i], Style.EMPTY))));
            }
            lines.add(Line.empty());
        }

        // Return Values section
        if (results != null && results.length > 0) {
            lines.add(Line.from(List.of(
                    Span.styled("━━ Return Values ━━", Style.EMPTY.fg(Color.CYAN).bold()))));
            for (int i = 0; i < results.length; i++) {
                var type = i < returnTypes.size() ? returnTypes.get(i) : ValType.I64;
                lines.add(Line.from(formatReturnValueSpans(i, type, results[i])));
            }
            lines.add(Line.empty());
        }

        // Status section
        lines.add(Line.from(List.of(
                Span.styled("━━ Status ━━", Style.EMPTY.fg(Color.CYAN).bold()))));
        if (hasError) {
            lines.add(Line.from(List.of(
                    Span.styled("  " + execError, Style.EMPTY.fg(Color.RED)))));
        } else if (hasWasiExit) {
            var exitStyle = exitCode == 0 ? Style.EMPTY.fg(Color.GREEN) : Style.EMPTY.fg(Color.YELLOW);
            lines.add(Line.from(List.of(
                    Span.styled("  WASI exit code: " + exitCode, exitStyle))));
        } else {
            lines.add(Line.from(List.of(
                    Span.styled("  Completed", Style.EMPTY.fg(Color.GREEN)))));
        }
        lines.add(Line.from(List.of(
                Span.styled("  Duration: " + durationMs + "ms", Style.EMPTY.dim()))));

        // stdout section
        if (!stdout.isEmpty()) {
            lines.add(Line.empty());
            lines.add(Line.from(List.of(
                    Span.styled("━━ stdout ━━", Style.EMPTY.fg(Color.CYAN).bold()))));
            for (var line : stdout.split("\n", -1)) {
                lines.add(Line.from(List.of(Span.styled(line, Style.EMPTY))));
            }
        }

        // stderr section
        if (!stderr.isEmpty()) {
            lines.add(Line.empty());
            lines.add(Line.from(List.of(
                    Span.styled("━━ stderr ━━", Style.EMPTY.fg(Color.RED).bold()))));
            for (var line : stderr.split("\n", -1)) {
                lines.add(Line.from(List.of(Span.styled(line, Style.EMPTY.fg(Color.RED)))));
            }
        }

        // Apply scrolling
        int start = Math.min(scrollOffset, Math.max(0, lines.size() - 1));
        var displayLines = lines.subList(start, lines.size());
        var styledText = Text.from(displayLines);

        var bottomTitle = paramTypes + " \u2192 " + returnTypes;
        var helpContent = row(text(" r").cyan().fit(), text(" re-run  ").dim().fit(),
                text("R").cyan().fit(), text(" reset  ").dim().fit(),
                text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                text("ESC/\u2190").cyan().fit(), text(" back").dim().fit());

        var contentPanel = panel(richText(styledText).overflow(Overflow.CLIP).fill())
                .title("Run: " + exportName)
                .bottomTitle(bottomTitle)
                .rounded().borderColor(borderColor).fill(1);
        return ViewLayout.layout(ctx, contentPanel, helpContent);
    }

    private List<Span> formatReturnValueSpans(int index, ValType type, long value) {
        var label = "  result " + index + " (" + type + "): ";
        if (ValType.I32.equals(type)) {
            int i = (int) value;
            return List.of(
                    Span.styled(label, Style.EMPTY.dim()),
                    Span.styled(String.valueOf(i), Style.EMPTY.fg(Color.GREEN).bold()),
                    Span.styled(" (0x" + Integer.toHexString(i) + ")", Style.EMPTY.dim()));
        } else if (ValType.I64.equals(type)) {
            return List.of(
                    Span.styled(label, Style.EMPTY.dim()),
                    Span.styled(String.valueOf(value), Style.EMPTY.fg(Color.GREEN).bold()),
                    Span.styled(" (0x" + Long.toHexString(value) + ")", Style.EMPTY.dim()));
        } else if (ValType.F32.equals(type)) {
            float f = Float.intBitsToFloat((int) value);
            return List.of(
                    Span.styled(label, Style.EMPTY.dim()),
                    Span.styled(String.valueOf(f), Style.EMPTY.fg(Color.GREEN).bold()));
        } else if (ValType.F64.equals(type)) {
            double d = Double.longBitsToDouble(value);
            return List.of(
                    Span.styled(label, Style.EMPTY.dim()),
                    Span.styled(String.valueOf(d), Style.EMPTY.fg(Color.GREEN).bold()));
        }
        return List.of(
                Span.styled(label, Style.EMPTY.dim()),
                Span.styled(String.valueOf(value), Style.EMPTY.fg(Color.GREEN).bold()));
    }
}
