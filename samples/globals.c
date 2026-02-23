#include <stdio.h>

// Mutable globals — visible in w9s Globals section
static int counter = 0;
static int accumulator = 0;

int increment(void) {
    counter++;
    accumulator += counter;
    return counter;
}

int get_counter(void) {
    return counter;
}

int get_accumulator(void) {
    return accumulator;
}

int main(void) {
    for (int i = 0; i < 10; i++) {
        int c = increment();
        printf("counter=%d  accumulator=%d\n", c, get_accumulator());
    }
    return 0;
}
