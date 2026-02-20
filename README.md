# w9s

Terminal UI for inspecting WebAssembly modules.

## Prerequisites

- Java 25+
- Maven 3.9+
- Rust with the `wasm32-wasip1` target (`rustup target add wasm32-wasip1`)
- GraalVM 25+ (optional, for native image builds)

## Build

### Java (shaded JAR)

```sh
mvn package
```

The Rust demangler WASM module at `src/main/wasm/rustc-demangle/` is compiled automatically as part of the Maven build.

### Native Image

```sh
mvn package -Pnative
```

## Run

### Java

```sh
java -jar target/w9s-999-SNAPSHOT.jar <file.wasm>
```

### Native Image

```sh
./target/w9s <file.wasm>
```

Both `.wasm` (binary) and `.wat` (text) files are supported.

## Usage

Use `↑`/`↓` to navigate sections, press `→` to drill into a section's details. Press `ESC` or `←` to go back.

From the **Code** section, press `Enter` on a function to open the **hex dump** view of its body. Press `Enter` again to toggle to the **WAT** (WebAssembly Text) view. Use `↑`/`↓` to scroll line by line, `PgUp`/`PgDn` to page, and `Home`/`End` to jump to the top or bottom.

Press `/` to search within hex or WAT content, then `n`/`N` to jump to the next or previous match.

Rust-mangled function names are automatically demangled using [`rustc-demangle`](https://crates.io/crates/rustc-demangle) compiled to WebAssembly and executed via [Chicory](https://github.com/nicholasgasior/chicory).
