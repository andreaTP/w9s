#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Global buffer visible in the Data/Memory view
static char buffer[256] = "initial data in the data segment";

// Exported so it can be called from w9s Run view
int fill_buffer(int pattern) {
    memset(buffer, pattern & 0xFF, sizeof(buffer) - 1);
    buffer[sizeof(buffer) - 1] = '\0';
    return (int)(size_t)buffer; // return the pointer for memory inspection
}

int get_buffer_addr(void) {
    return (int)(size_t)buffer;
}

int main(void) {
    printf("buffer at: %p\n", (void *)buffer);
    printf("contents:  %s\n", buffer);

    // Allocate on the heap — shows linear memory growth
    char *heap = malloc(128);
    if (heap) {
        strcpy(heap, "heap-allocated string");
        printf("heap at:   %p\n", (void *)heap);
        printf("contents:  %s\n", heap);
        free(heap);
    }

    // Fill buffer with 'A' and print
    fill_buffer('A');
    printf("filled:    %s\n", buffer);

    return 0;
}
