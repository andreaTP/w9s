#include <stdio.h>

// Collatz conjecture: interesting control flow to inspect in WAT view
int collatz_steps(int n) {
    int steps = 0;
    while (n != 1) {
        if (n % 2 == 0) {
            n = n / 2;
        } else {
            n = 3 * n + 1;
        }
        steps++;
    }
    return steps;
}

int main(void) {
    for (int i = 1; i <= 30; i++) {
        printf("collatz(%d) = %d steps\n", i, collatz_steps(i));
    }
    return 0;
}
