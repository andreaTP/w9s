#include <stdio.h>

// Exported recursive fibonacci — interesting to trace in the WAT view
int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main(void) {
    for (int i = 0; i < 20; i++) {
        printf("fib(%d) = %d\n", i, fib(i));
    }
    return 0;
}
