package io.roastedroot.w9s;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class InstanceManager {

    private final WasmModule module;
    private Instance wasmInstance;
    private WasiPreview1 wasi;
    private String instanceError;
    private boolean instanceNeedsReset;
    private ByteArrayOutputStream wasiStdoutCapture;
    private ByteArrayOutputStream wasiStderrCapture;

    InstanceManager(WasmModule module) {
        this.module = module;
    }

    boolean ensureInstance() {
        if (instanceNeedsReset) {
            if (wasi != null) {
                wasi.close();
                wasi = null;
            }
            wasmInstance = null;
            instanceError = null;
            instanceNeedsReset = false;
        }
        if (instanceError != null) {
            return false;
        }
        if (wasmInstance != null) {
            return true;
        }

        // Check for unresolvable imports
        var unresolvable = findUnresolvableImports();
        if (!unresolvable.isEmpty()) {
            var sb = new StringBuilder("Module has unresolvable imports:\n");
            for (var imp : unresolvable) {
                sb.append("  ").append(imp).append('\n');
            }
            instanceError = sb.toString();
            return false;
        }

        try {
            var builder = ImportValues.builder();
            if (moduleNeedsWasi()) {
                wasiStdoutCapture = new ByteArrayOutputStream();
                wasiStderrCapture = new ByteArrayOutputStream();
                var wasiOpts =
                        WasiOptions.builder()
                                .withStdout(wasiStdoutCapture)
                                .withStderr(wasiStderrCapture)
                                .build();
                wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
                builder.addFunction(wasi.toHostFunctions());
            }
            var imports = builder.build();
            wasmInstance =
                    Instance.builder(module)
                            .withImportValues(imports)
                            .withStart(false)
                            .build();
            return true;
        } catch (Exception e) {
            instanceError = "Failed to instantiate module: " + e.getMessage();
            return false;
        }
    }

    Instance instance() {
        return wasmInstance;
    }

    Memory memory() {
        return wasmInstance.memory();
    }

    String instanceError() {
        return instanceError;
    }

    void requestReset() {
        instanceNeedsReset = true;
    }

    ByteArrayOutputStream stdoutCapture() {
        return wasiStdoutCapture;
    }

    ByteArrayOutputStream stderrCapture() {
        return wasiStderrCapture;
    }

    void close() {
        if (wasi != null) {
            wasi.close();
        }
    }

    private boolean moduleNeedsWasi() {
        var is = module.importSection();
        for (int i = 0; i < is.importCount(); i++) {
            var imp = is.getImport(i);
            if ("wasi_snapshot_preview1".equals(imp.module())) {
                return true;
            }
        }
        return false;
    }

    private List<String> findUnresolvableImports() {
        var unresolvable = new ArrayList<String>();
        var is = module.importSection();
        for (int i = 0; i < is.importCount(); i++) {
            var imp = is.getImport(i);
            if (!"wasi_snapshot_preview1".equals(imp.module())) {
                unresolvable.add(imp.module() + "." + imp.name()
                        + " (" + imp.importType().name().toLowerCase() + ")");
            }
        }
        return unresolvable;
    }
}
