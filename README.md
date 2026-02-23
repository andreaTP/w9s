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

### Navigation

| Key | Action |
|-----|--------|
| `↑`/`↓` | Navigate / scroll |
| `→` / `Enter` | Drill into section details |
| `ESC` / `←` | Go back |
| `PgUp`/`PgDn` | Page up / down |
| `Home`/`End` | Jump to top / bottom |
| `/` | Search / filter |
| `q` | Quit |

### Section-specific keys

| Key | View | Action |
|-----|------|--------|
| `Enter` | **Code** | Open function hex/WAT view |
| `Enter` | **Functions** | Jump to Code section |
| `Enter` | **Imports** | Jump to Type definition |
| `Enter` | **Exports** | View / enter sub-view |
| `Enter` | **Data** | View hex dump |
| `r` | **Exports** | Run exported function |
| `e` | **Globals** | Edit mutable global value |

### Function view (Code → Enter)

| Key | Action |
|-----|--------|
| `Enter` | Toggle hex dump / WAT view |
| `/` | Search in content |
| `n` / `N` | Next / previous match |
| `↑`/`↓` | Scroll line by line |
| `PgUp`/`PgDn` | Page up / down |

### Memory editor (Exports/Memories → Enter on memory)

| Key | Action |
|-----|--------|
| `g` | Go to address |
| `w` | Write string at address |
| `e` | Write typed value (i32/i64/f32/f64) at address |
| `Ctrl+T` | Toggle null-termination (in write-string mode) |
| `↑`/`↓` | Scroll memory |
| `Enter` | Confirm input |
| `ESC` | Cancel input / exit |

### Search & filter

Press `/` in any detail view to filter rows. The filter matches on index numbers and, in views that display names (Functions, Code, Exports, Imports), also matches on names (case-insensitive substring).

In function hex/WAT views, `/` performs a content search. Use `n`/`N` to navigate matches.

### Notes

Rust-mangled function names are automatically demangled using [`rustc-demangle`](https://crates.io/crates/rustc-demangle) compiled to WebAssembly and executed via [Chicory](https://github.com/nicholasgasior/chicory).
