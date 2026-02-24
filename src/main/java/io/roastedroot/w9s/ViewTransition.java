package io.roastedroot.w9s;

public sealed interface ViewTransition {
    record ToSectionNav() implements ViewTransition {}

    record ToDetailView() implements ViewTransition {}

    record ToDetailViewAt(int sectionIdx, int detailIdx) implements ViewTransition {}

    record ToFunctionView(int funcIdx) implements ViewTransition {}

    record ToDataView(int dataIdx) implements ViewTransition {}

    record ToRunParamView(String exportName) implements ViewTransition {}

    record ToRunOutputView(String exportName) implements ViewTransition {}

    record ToMemoryView() implements ViewTransition {}

    record Quit() implements ViewTransition {}
}
