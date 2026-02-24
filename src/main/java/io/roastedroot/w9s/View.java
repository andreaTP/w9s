package io.roastedroot.w9s;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

public sealed interface View
        permits SectionNavView,
                DetailView,
                FunctionView,
                DataView,
                RunParamView,
                RunOutputView,
                MemoryView {

    EventResult handleKey(KeyEvent key, ViewContext ctx);

    Element render(ViewContext ctx);
}
