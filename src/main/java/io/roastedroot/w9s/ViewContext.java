package io.roastedroot.w9s;

import com.dylibso.chicory.wasm.WasmModule;
import dev.tamboui.widgets.table.TableState;
import java.util.List;

public final class ViewContext {

    private final String filename;
    private final WasmModule module;
    private final List<String[]> sectionRows;
    private final TableState sectionTableState;
    private final InstanceManager instanceManager;
    private final FunctionDataProvider functionData;
    private ViewTransition pendingTransition;
    private RunOutputView pendingRunOutputView;

    ViewContext(
            String filename,
            WasmModule module,
            List<String[]> sectionRows,
            TableState sectionTableState,
            InstanceManager instanceManager,
            FunctionDataProvider functionData) {
        this.filename = filename;
        this.module = module;
        this.sectionRows = sectionRows;
        this.sectionTableState = sectionTableState;
        this.instanceManager = instanceManager;
        this.functionData = functionData;
    }

    public String filename() {
        return filename;
    }

    public WasmModule module() {
        return module;
    }

    public List<String[]> sectionRows() {
        return sectionRows;
    }

    public TableState sectionTableState() {
        return sectionTableState;
    }

    public InstanceManager instanceManager() {
        return instanceManager;
    }

    public FunctionDataProvider functionData() {
        return functionData;
    }

    public void navigateTo(ViewTransition transition) {
        this.pendingTransition = transition;
    }

    ViewTransition consumeTransition() {
        var t = pendingTransition;
        pendingTransition = null;
        return t;
    }

    void setPendingRunOutputView(RunOutputView view) {
        this.pendingRunOutputView = view;
    }

    RunOutputView consumePendingRunOutputView() {
        var v = pendingRunOutputView;
        pendingRunOutputView = null;
        return v;
    }

    public int sectionIndex(String name) {
        for (int i = 0; i < sectionRows.size(); i++) {
            if (name.equals(sectionRows.get(i)[0])) {
                return i;
            }
        }
        return -1;
    }

    public String selectedSectionName() {
        var idx = sectionTableState.selected() != null ? sectionTableState.selected() : 0;
        return idx < sectionRows.size() ? sectionRows.get(idx)[0] : "";
    }
}
