package io.roastedroot.w9s;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.ValType;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.table.TableState;
import java.time.Duration;
import java.util.List;

public final class W9sApp {

    private final ViewContext ctx;
    private View activeView;
    private DetailView detailView;

    public W9sApp(String filename, WasmModule module, byte[] wasmBytes) {
        var sectionRows = WasmUtils.buildSectionRows(module);
        var tableState = new TableState();
        tableState.select(0);
        var instanceManager = new InstanceManager(module);
        var functionData = new FunctionDataProvider(module, wasmBytes);
        this.ctx = new ViewContext(filename, module, sectionRows, tableState,
                instanceManager, functionData);
        this.detailView = new DetailView();
        this.activeView = new SectionNavView();
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
            ctx.instanceManager().close();
            ctx.functionData().close();
        }
    }

    private EventResult handleKey(KeyEvent key, ToolkitRunner runner) {
        var result = activeView.handleKey(key, ctx);
        var transition = ctx.consumeTransition();
        if (transition != null) {
            processTransition(transition, runner);
        }
        return result;
    }

    private Element render() {
        return activeView.render(ctx);
    }

    private void processTransition(ViewTransition transition, ToolkitRunner runner) {
        switch (transition) {
            case ViewTransition.Quit q -> runner.quit();
            case ViewTransition.ToSectionNav n -> activeView = new SectionNavView();
            case ViewTransition.ToDetailView d -> { detailView.resetFilter(); activeView = detailView; }
            case ViewTransition.ToDetailViewAt at -> {
                ctx.sectionTableState().select(at.sectionIdx());
                detailView.detailTableState().select(at.detailIdx());
                activeView = detailView;
            }
            case ViewTransition.ToFunctionView f -> activeView = new FunctionView(f.funcIdx());
            case ViewTransition.ToDataView d -> activeView = new DataView(d.dataIdx());
            case ViewTransition.ToRunParamView r -> handleRunExport(r.exportName());
            case ViewTransition.ToRunOutputView r -> {
                var pending = ctx.consumePendingRunOutputView();
                if (pending != null) {
                    activeView = pending;
                }
            }
            case ViewTransition.ToMemoryView m -> activeView = new MemoryView();
        }
    }

    private void handleRunExport(String exportName) {
        var module = ctx.module();
        // Find the export by name
        var es = module.exportSection();
        int expIdx = -1;
        for (int i = 0; i < es.exportCount(); i++) {
            if (es.getExport(i).name().equals(exportName)) {
                expIdx = i;
                break;
            }
        }
        if (expIdx < 0) return;

        var exp = es.getExport(expIdx);
        if (exp.exportType() != ExternalType.FUNCTION) return;

        int importedFuncs = module.importSection().count(ExternalType.FUNCTION);
        int localIdx = (int) exp.index() - importedFuncs;

        // Get function type
        int typeIdx;
        if (localIdx >= 0 && localIdx < module.functionSection().functionCount()) {
            typeIdx = module.functionSection().getFunctionType(localIdx);
        } else {
            var imp = module.importSection().getImport((int) exp.index());
            if (imp instanceof FunctionImport funcImport) {
                typeIdx = funcImport.typeIndex();
            } else {
                return;
            }
        }
        var ft = module.typeSection().getType(typeIdx);
        List<ValType> paramTypes = ft.params();
        List<ValType> returnTypes = ft.returns();

        // Check for reference types
        for (var pt : paramTypes) {
            if (pt.isReference()) {
                var outputView = new RunOutputView(exportName, paramTypes, returnTypes, null);
                outputView.setError("Reference type parameters are not supported");
                activeView = outputView;
                return;
            }
        }

        if (!ctx.instanceManager().ensureInstance()) {
            var outputView = new RunOutputView(exportName, paramTypes, returnTypes, null);
            outputView.setError(ctx.instanceManager().instanceError());
            activeView = outputView;
            return;
        }

        if (paramTypes.isEmpty()) {
            RunOutputView.executeAndTransition(ctx, exportName, paramTypes, returnTypes,
                    new String[0], new long[0]);
            var pending = ctx.consumePendingRunOutputView();
            if (pending != null) {
                activeView = pending;
            }
        } else {
            activeView = new RunParamView(exportName, paramTypes, returnTypes);
        }
    }
}
