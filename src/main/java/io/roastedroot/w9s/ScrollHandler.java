package io.roastedroot.w9s;

import dev.tamboui.tui.event.KeyEvent;

final class ScrollHandler {

    private ScrollHandler() {}

    /**
     * Returns the new scroll offset, or -1 if the key was not a scroll key.
     */
    static int handleKey(KeyEvent key, int currentOffset) {
        if (key.isUp()) { return Math.max(0, currentOffset - 1); }
        if (key.isDown()) { return currentOffset + 1; }
        if (key.isPageUp()) { return Math.max(0, currentOffset - ViewLayout.PAGE_SIZE); }
        if (key.isPageDown()) { return currentOffset + ViewLayout.PAGE_SIZE; }
        if (key.isHome()) { return 0; }
        if (key.isEnd()) { return Integer.MAX_VALUE; }
        return -1;
    }
}
