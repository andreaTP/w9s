#include <stdio.h>

__attribute__((export_name("greet")))
void greet(const char *name) {
    printf("Hello, %s!\n", name);
}

int main(void) {
    greet("World");
    return 0;
}
