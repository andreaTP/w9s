package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasm.types.ValType;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.charset.StandardCharsets;
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

        var sb = new StringBuilder();
        sb.append("Run: ").append(exportName).append('\n');
        if (paramValues != null && paramValues.length > 0) {
            sb.append("\n-- Input Parameters --\n");
            for (int i = 0; i < paramValues.length; i++) {
                sb.append("  param ").append(i).append(" (").append(paramTypes.get(i)).append("): ").append(paramValues[i]).append('\n');
            }
        }
        if (results != null && results.length > 0) {
            sb.append("\n-- Return Values --\n");
            for (int i = 0; i < results.length; i++) {
                var type = i < returnTypes.size() ? returnTypes.get(i) : ValType.I64;
                sb.append("  result ").append(i).append(" (").append(type).append("): ").append(ParamUtils.formatReturnValue(type, results[i])).append('\n');
            }
        }
        sb.append("\n-- Status --\n");
        if (hasError) sb.append("  ").append(execError).append('\n');
        else if (hasWasiExit) sb.append("  WASI exit code: ").append(exitCode).append('\n');
        else sb.append("  Completed");
        sb.append("  Duration: ").append(durationMs).append("ms\n");
        if (!stdout.isEmpty()) { sb.append("\n-- stdout --\n").append(stdout); if (!stdout.endsWith("\n")) sb.append('\n'); }
        if (!stderr.isEmpty()) { sb.append("\n-- stderr --\n").append(stderr); if (!stderr.endsWith("\n")) sb.append('\n'); }

        var scrolled = WasmUtils.scrollContent(sb.toString(), scrollOffset);
        var helpContent = row(text(" r").cyan().fit(), text(" re-run  ").dim().fit(),
                text("R").cyan().fit(), text(" reset  ").dim().fit(),
                text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                text("ESC/\u2190").cyan().fit(), text(" back").dim().fit());

        var contentPanel = panel(richText(scrolled).overflow(Overflow.CLIP).fill()).title("Run: " + exportName).rounded().borderColor(borderColor).fill(1);
        return ViewLayout.layout(ctx, contentPanel, helpContent);
    }
}
