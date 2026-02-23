#!/usr/bin/env bash
#
# Build all samples to WASM targeting WASI.
#
# Requirements:
#   C samples : WASI SDK  — https://github.com/WebAssembly/wasi-sdk
#   Rust samples: rustup target add wasm32-wasip1
#
# Usage:
#   ./build.sh                          # auto-detect WASI SDK
#   WASI_SDK_PATH=/opt/wasi-sdk ./build.sh   # explicit path
#
set -euo pipefail
cd "$(dirname "$0")"

# ---------- locate WASI SDK ----------
if [ -z "${WASI_SDK_PATH:-}" ]; then
    # common install locations
    for candidate in /opt/wasi-sdk /usr/local/wasi-sdk "$HOME/wasi-sdk"; do
        if [ -x "$candidate/bin/clang" ]; then
            WASI_SDK_PATH="$candidate"
            break
        fi
    done
fi

# ---------- C samples ----------
if [ -n "${WASI_SDK_PATH:-}" ]; then
    CC="${WASI_SDK_PATH}/bin/clang"
    CFLAGS="--sysroot=${WASI_SDK_PATH}/share/wasi-sysroot -O2 -Wl,--export-all"
    echo "Using WASI SDK at ${WASI_SDK_PATH}"

    for src in *.c; do
        out="${src%.c}.wasm"
        echo "  CC  ${src} -> ${out}"
        $CC $CFLAGS -o "$out" "$src"
    done
else
    echo "WASI SDK not found — skipping C samples."
    echo "Set WASI_SDK_PATH or install from https://github.com/WebAssembly/wasi-sdk"
fi

# ---------- Rust samples ----------
if command -v rustc &>/dev/null; then
    # ensure the target is installed
    rustup target add wasm32-wasip1 2>/dev/null || true

    for src in *.rs; do
        out="${src%.rs}-rs.wasm"
        echo "  RUSTC  ${src} -> ${out}"
        rustc --target wasm32-wasip1 --edition 2021 -O -o "$out" "$src"
    done
else
    echo "rustc not found — skipping Rust samples."
fi

echo "Done."
