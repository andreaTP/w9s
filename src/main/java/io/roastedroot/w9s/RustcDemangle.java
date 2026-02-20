package io.roastedroot.w9s;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import java.nio.charset.StandardCharsets;

public final class RustcDemangle implements AutoCloseable {

    private static final WasmModule MODULE = RustcDemangleModule.load();

    private final WasiPreview1 wasi;
    private final Memory memory;
    private final ExportFunction alloc;
    private final ExportFunction dealloc;
    private final ExportFunction rustcDemangleLen;
    private final ExportFunction rustcDemangle;

    public RustcDemangle() {
        var wasiOpts = WasiOptions.builder().inheritSystem().build();
        this.wasi = WasiPreview1.builder().withOptions(wasiOpts).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
        var instance =
                Instance.builder(MODULE)
                        .withMachineFactory(RustcDemangleModule::create)
                        .withImportValues(imports)
                        .build();

        this.memory = instance.memory();
        this.alloc = instance.export("alloc");
        this.dealloc = instance.export("dealloc");
        this.rustcDemangleLen = instance.export("rustc_demangle_len");
        this.rustcDemangle = instance.export("rustc_demangle");
    }

    /**
     * Demangle a Rust symbol name. If the name is not a valid mangled Rust symbol,
     * returns the original name unchanged.
     */
    public String demangle(String mangled) {
        if (mangled == null || mangled.isEmpty()) {
            return mangled;
        }

        // Write the null-terminated mangled string into WASM memory
        var mangledBytes = mangled.getBytes(StandardCharsets.UTF_8);
        int mangledLen = mangledBytes.length + 1; // +1 for null terminator
        int mangledPtr = (int) alloc.apply(mangledLen)[0];
        memory.write(mangledPtr, mangledBytes);
        memory.writeByte(mangledPtr + mangledBytes.length, (byte) 0);

        try {
            // Get required output buffer size
            int outLen = (int) rustcDemangleLen.apply(mangledPtr)[0];
            if (outLen == 0) {
                // Not a valid Rust symbol, return original
                return mangled;
            }

            // Allocate output buffer and demangle
            int outPtr = (int) alloc.apply(outLen)[0];
            try {
                int result = (int) rustcDemangle.apply(mangledPtr, outPtr, outLen)[0];
                if (result == 1) {
                    // Read null-terminated result
                    return memory.readCString(outPtr);
                }
                return mangled;
            } finally {
                dealloc.apply(outPtr, outLen);
            }
        } finally {
            dealloc.apply(mangledPtr, mangledLen);
        }
    }

    @Override
    public void close() {
        if (wasi != null) {
            wasi.close();
        }
    }
}
