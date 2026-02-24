package io.roastedroot.w9s;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

public final class ContentSearchState {

    private boolean inContentSearch = false;
    private String contentSearchQuery = "";
    private int contentSearchMatchLine = -1;

    boolean isActive() {
        return inContentSearch;
    }

    String query() {
        return contentSearchQuery;
    }

    int matchLine() {
        return contentSearchMatchLine;
    }

    void startSearch() {
        inContentSearch = true;
        contentSearchQuery = "";
        contentSearchMatchLine = -1;
    }

    void reset() {
        inContentSearch = false;
        contentSearchQuery = "";
        contentSearchMatchLine = -1;
    }

    void cancelSearch() {
        inContentSearch = false;
        contentSearchQuery = "";
        contentSearchMatchLine = -1;
    }

    EventResult handleKey(KeyEvent key, ScrollTarget scrollTarget) {
        if (key.isCancel()) {
            cancelSearch();
            return EventResult.HANDLED;
        }
        if (key.isConfirm()) {
            inContentSearch = false;
            if (!contentSearchQuery.isEmpty()) {
                searchNext(scrollTarget);
            }
            return EventResult.HANDLED;
        }
        if (key.isDeleteBackward() && !contentSearchQuery.isEmpty()) {
            contentSearchQuery = contentSearchQuery.substring(0, contentSearchQuery.length() - 1);
            return EventResult.HANDLED;
        }
        if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) {
            contentSearchQuery += key.character();
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    void searchNext(ScrollTarget target) {
        var content = target.contentText();
        var lines = content.split("\n", -1);
        var query = contentSearchQuery.toLowerCase();
        int startLine = target.scrollOffset() + 1;
        for (int i = startLine; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                target.setScrollOffset(i);
                return;
            }
        }
        // Wrap around from the beginning
        for (int i = 0; i < startLine && i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                target.setScrollOffset(i);
                return;
            }
        }
        contentSearchMatchLine = -1;
    }

    void searchPrev(ScrollTarget target) {
        var content = target.contentText();
        var lines = content.split("\n", -1);
        var query = contentSearchQuery.toLowerCase();
        int startLine = target.scrollOffset() - 1;
        for (int i = startLine; i >= 0; i--) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                target.setScrollOffset(i);
                return;
            }
        }
        // Wrap around from the end
        for (int i = lines.length - 1; i > startLine; i--) {
            if (lines[i].toLowerCase().contains(query)) {
                contentSearchMatchLine = i;
                target.setScrollOffset(i);
                return;
            }
        }
        contentSearchMatchLine = -1;
    }

    interface ScrollTarget {
        String contentText();

        int scrollOffset();

        void setScrollOffset(int offset);
    }
}
