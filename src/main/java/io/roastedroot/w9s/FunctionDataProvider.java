package io.roastedroot.w9s;

import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import io.roastedroot.lumis4j.core.Formatter;
import io.roastedroot.lumis4j.core.Highlighter;
import io.roastedroot.lumis4j.core.Lang;
import io.roastedroot.lumis4j.core.Lumis;
import io.roastedroot.lumis4j.core.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class FunctionDataProvider {

    private final WasmModule module;
    private final List<byte[]> functionBodies;
    private final CompletableFuture<List<String>> functionWatsFuture;
    private final CompletableFuture<List<String>> functionNamesFuture;
    private final java.util.Map<Integer, String> highlightedWatCache = new java.util.HashMap<>();

    private volatile Lumis lumis;
    private volatile Highlighter watHighlighter;
    private final CompletableFuture<Void> highlighterReady;

    FunctionDataProvider(WasmModule module, byte[] wasmBytes) {
        this.module = module;
        this.functionBodies = WasmUtils.extractFunctionBodies(wasmBytes);
        this.functionNamesFuture = CompletableFuture.supplyAsync(() -> buildFunctionNames(module));

        this.functionWatsFuture =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return WasmUtils.extractFunctions(Wasm2Wat.print(wasmBytes));
                            } catch (Exception e) {
                                return List.of();
                            }
                        });

        this.highlighterReady =
                CompletableFuture.runAsync(
                        () -> {
                            this.lumis = Lumis.builder().build();
                            this.watHighlighter =
                                    lumis.highlighter()
                                            .withLang(Lang.WAT)
                                            .withTheme(Theme.DRACULA)
                                            .withFormatter(Formatter.TERMINAL)
                                            .build();
                        });
    }

    List<byte[]> functionBodies() {
        return functionBodies;
    }

    CompletableFuture<List<String>> functionWatsFuture() {
        return functionWatsFuture;
    }

    java.util.Map<Integer, String> highlightedWatCache() {
        return highlightedWatCache;
    }

    Highlighter watHighlighter() {
        return watHighlighter;
    }

    CompletableFuture<Void> highlighterReady() {
        return highlighterReady;
    }

    String functionName(int localFuncIdx) {
        if (functionNamesFuture.isDone() && !functionNamesFuture.isCompletedExceptionally()) {
            var names = functionNamesFuture.join();
            if (localFuncIdx >= 0 && localFuncIdx < names.size()) {
                var name = names.get(localFuncIdx);
                if (name != null) {
                    return name;
                }
            }
        }
        return "func #" + localFuncIdx;
    }

    void close() {
        highlighterReady.join();
        if (lumis != null) {
            lumis.close();
        }
    }

    private static List<String> buildFunctionNames(WasmModule module) {
        int importedFuncs = module.importSection().count(ExternalType.FUNCTION);
        int localFuncCount = module.functionSection().functionCount();
        var names = new ArrayList<String>(localFuncCount);

        // Initialize with name section names (uses absolute indices)
        var nameSection = module.nameSection();
        for (int i = 0; i < localFuncCount; i++) {
            int absIdx = importedFuncs + i;
            String name = null;
            if (nameSection != null) {
                name = nameSection.nameOfFunction(absIdx);
            }
            names.add(name);
        }

        // Fill in from exports where name section didn't have a name
        var es = module.exportSection();
        for (int i = 0; i < es.exportCount(); i++) {
            var exp = es.getExport(i);
            if (exp.exportType() == ExternalType.FUNCTION) {
                int localIdx = (int) exp.index() - importedFuncs;
                if (localIdx >= 0 && localIdx < localFuncCount && names.get(localIdx) == null) {
                    names.set(localIdx, exp.name());
                }
            }
        }

        // Demangle all names
        try (var demangler = new RustcDemangle()) {
            for (int i = 0; i < names.size(); i++) {
                var name = names.get(i);
                if (name != null) {
                    names.set(i, demangler.demangle(name));
                }
            }
        }

        return names;
    }
}
