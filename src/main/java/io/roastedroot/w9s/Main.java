package io.roastedroot.w9s;

import com.dylibso.chicory.tools.wasm.Wat2Wasm;
import com.dylibso.chicory.wasm.Parser;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "w9s",
        mixinStandardHelpOptions = true,
        version = "w9s 0.1.0",
        description = "Terminal UI for inspecting WebAssembly modules")
public final class Main implements Callable<Integer> {

    @Parameters(
            index = "0",
            description = "The WebAssembly (.wasm) or WAT (.wat) file to inspect")
    private File wasmFile;

    @Override
    public Integer call() throws Exception {
        byte[] wasmBytes;
        if (wasmFile.getName().endsWith(".wat")) {
            wasmBytes = Wat2Wasm.parse(wasmFile);
        } else {
            wasmBytes = Files.readAllBytes(wasmFile.toPath());
        }
        var module = Parser.parse(new ByteArrayInputStream(wasmBytes));
        var app = new W9sApp(wasmFile.getName(), module, wasmBytes);
        app.run();
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
