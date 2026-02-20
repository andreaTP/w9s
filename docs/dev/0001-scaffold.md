# w9s

In this project I want to start working (on and off no full time, so let's keep good track of the progress) on a Terminal UI for inspecting Wasm modules.

This is the reference WASM specification: https://webassembly.github.io/spec/core/_download/WebAssembly.pdf

We want to leverage Chicory and we will build on top of https://github.com/dylibso/chicory/tree/main/wasm (locally at ../chicory feel free to read from there).

In addition to pure Chicory "wasm" module to read the structure of wasm we can use `wasm-tools` behind the scenes compiled to Java.

For the functionality we want to use https://tamboui.dev/ it's a young project but we can help it improving, that's part of the game.

We should be able to compile everything to native using GraalVM native image.

## Functionality

The user would type `w9s my_wasm_payload.wasm` and we want to borrow as much experience from k9s: https://k9scli.io/
