package io.roastedroot.w9s;

import com.dylibso.chicory.log.Logger;
import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.tools.wasm.WasmToolsModule;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Wasm2Wat {
    private Wasm2Wat() {}

    private static final Logger logger =
            new SystemLogger() {
                @Override
                public boolean isLoggable(Logger.Level level) {
                    return false;
                }
            };
    private static final WasmModule MODULE = WasmToolsModule.load();

    public static String print(byte[] wasmBytes) {
        try (var stdinStream = new ByteArrayInputStream(wasmBytes);
                var stdoutStream = new ByteArrayOutputStream();
                var stderrStream = new ByteArrayOutputStream()) {

            var wasiOpts =
                    WasiOptions.builder()
                            .withStdin(stdinStream)
                            .withStdout(stdoutStream)
                            .withStderr(stderrStream)
                            .withArguments(List.of("wasm-tools", "print", "-"))
                            .build();

            try (var wasi =
                    WasiPreview1.builder()
                            .withLogger(logger)
                            .withOptions(wasiOpts)
                            .build()) {
                var imports =
                        ImportValues.builder()
                                .addFunction(wasi.toHostFunctions())
                                .build();

                Instance.builder(MODULE)
                        .withMachineFactory(WasmToolsModule::create)
                        .withMemoryFactory(ByteArrayMemory::new)
                        .withImportValues(imports)
                        .build();
            } catch (WasiExitException e) {
                if (e.exitCode() != 0 || stdoutStream.size() <= 0) {
                    throw new RuntimeException(
                            "wasm-tools print failed: "
                                    + stdoutStream.toString(StandardCharsets.UTF_8)
                                    + stderrStream.toString(StandardCharsets.UTF_8),
                            e);
                }
            }

            return stdoutStream.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
