#!/usr/bin/env bash
#
# Record asciinema demos of w9s and convert to GIF via agg.
#
# Usage: ./record-all.sh
#
# Prerequisites: asciinema, agg, tmux
#
set -euo pipefail
cd "$(dirname "$0")"

W9S="$(cd ../.. && pwd)/target/w9s"
SAMPLES="$(cd ../.. && pwd)/samples"

if [ ! -x "$W9S" ]; then
    echo "ERROR: native binary not found at $W9S"
    echo "Build with: mvn package -Pnative -DskipTests"
    exit 1
fi

COLS=100
ROWS=30
SESSION="w9s-demo"

# Helper: run a scripted demo inside tmux, recorded by asciinema
# Usage: record_demo <cast_file> <wasm_file> <keypress_function>
record_demo() {
    local cast="$1" wasm="$2" script_fn="$3"

    # Kill any existing session
    tmux kill-session -t "$SESSION" 2>/dev/null || true

    # Start a detached tmux session with controlled size
    tmux new-session -d -s "$SESSION" -x "$COLS" -y "$ROWS"

    # Start asciinema recording inside tmux, launching w9s
    tmux send-keys -t "$SESSION" \
        "asciinema rec --cols $COLS --rows $ROWS --overwrite '$cast' -c '$W9S $wasm'" Enter

    # Wait for w9s to start up
    sleep 4

    # Run the scripted keypresses
    $script_fn

    # Clean up
    tmux kill-session -t "$SESSION" 2>/dev/null || true
}

send() { tmux send-keys -t "$SESSION" "$@"; }
pause() { sleep "${1:-1}"; }

# ============================================================
# Demo 1: Browse WAT & Hex views (Rust hello)
# ============================================================
demo_wat_hex() {
    # Navigate to Code section (arrow down)
    pause 1
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4
    send Down;  pause 0.4   # Code section

    # Enter detail view
    send Right; pause 0.8

    # Filter to find greet function
    send /;     pause 0.5
    send g r e e t; pause 1
    send Enter; pause 0.8

    # Enter function view (hex dump)
    send Enter; pause 1.5

    # Scroll down a bit in hex view
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    pause 1

    # Toggle to WAT view
    send Enter; pause 2

    # Scroll through WAT
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    pause 1.5

    # Go back
    send Escape; pause 0.5
    send Escape; pause 0.5

    # Quit
    send q
    pause 1
}

# ============================================================
# Demo 2: Run hello world (C)
# ============================================================
demo_hello_c() {
    # Navigate to Exports
    pause 1
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3   # Exports

    # Enter detail view
    send Right; pause 0.8

    # Filter to find main
    send /;     pause 0.5
    send m a i n; pause 1
    send Enter; pause 0.5

    # Run with 'r'
    send r;     pause 3

    # Show output for a moment
    pause 3

    # Go back
    send Escape; pause 0.5
    send Escape; pause 0.5

    # Quit
    send q
    pause 1
}

# ============================================================
# Demo 3: Run hello world (Rust)
# ============================================================
demo_hello_rs() {
    # Navigate to Exports
    pause 1
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3   # Exports

    # Enter detail view
    send Right; pause 0.8

    # Filter to find main
    send /;     pause 0.5
    send m a i n; pause 0.8
    send Enter; pause 0.5

    # Navigate down to find __original_main or _start
    send Down;  pause 0.3
    send Down;  pause 0.3

    # Run with 'r'
    send r;     pause 3

    # Show output for a moment
    pause 3

    # Go back
    send Escape; pause 0.5
    send Escape; pause 0.5

    # Quit
    send q
    pause 1
}

# ============================================================
# Demo 4: Memory inspection
# ============================================================
demo_memory() {
    # Navigate to Exports
    pause 1
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3   # Exports

    # Enter detail view
    send Right; pause 0.8

    # Filter to find main and run it first to populate memory
    send /;     pause 0.5
    send m a i n; pause 0.8
    send Enter; pause 0.5
    send r;     pause 3

    # View output
    pause 2

    # Go back to exports
    send Escape; pause 0.5

    # Clear filter, find memory export
    send /;     pause 0.3
    send m e m o r y; pause 0.8
    send Enter; pause 0.5

    # Enter memory view
    send Enter; pause 1.5

    # Scroll through memory
    send Down;  pause 0.3
    send Down;  pause 0.3
    send Down;  pause 0.3
    pause 1

    # Goto an address
    send g;     pause 0.5
    send 1 0 2 4; pause 0.8
    send Enter; pause 1.5

    # Write a string
    send w;     pause 0.5
    send 1 0 2 4; pause 0.5
    send Enter; pause 0.5
    send 'H' 'e' 'l' 'l' 'o' ' ' 'w' '9' 's' '!'; pause 1
    send Enter; pause 1.5

    # Show the result
    pause 2

    # Go back and quit
    send Escape; pause 0.5
    send Escape; pause 0.5
    send q
    pause 1
}

# ============================================================
# Record all demos
# ============================================================
echo "=== Recording Demo 1: WAT & Hex views (hello-rs.wasm) ==="
record_demo "wat-hex.cast" "$SAMPLES/hello-rs.wasm" demo_wat_hex

echo "=== Recording Demo 2: Hello World C (hello.wasm) ==="
record_demo "hello-c.cast" "$SAMPLES/hello.wasm" demo_hello_c

echo "=== Recording Demo 3: Hello World Rust (hello-rs.wasm) ==="
record_demo "hello-rs.cast" "$SAMPLES/hello-rs.wasm" demo_hello_rs

echo "=== Recording Demo 4: Memory inspection (memory.wasm) ==="
record_demo "memory.cast" "$SAMPLES/memory.wasm" demo_memory

# ============================================================
# Convert to GIF
# ============================================================
echo ""
echo "=== Converting to GIF ==="
for cast in wat-hex.cast hello-c.cast hello-rs.cast memory.cast; do
    gif="${cast%.cast}.gif"
    echo "  $cast -> $gif"
    agg --cols "$COLS" --rows "$ROWS" --font-size 14 "$cast" "$gif"
done

echo ""
echo "Done! GIFs are in $(pwd)/"
ls -lh *.gif
