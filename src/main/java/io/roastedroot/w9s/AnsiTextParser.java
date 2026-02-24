package io.roastedroot.w9s;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import java.util.ArrayList;
import java.util.List;

public final class AnsiTextParser {

    private AnsiTextParser() {}

    static Text parseAnsiText(String ansiString) {
        var lines = new ArrayList<Line>();
        var spans = new ArrayList<Span>();
        var currentText = new StringBuilder();
        var currentStyle = Style.EMPTY;

        int i = 0;
        while (i < ansiString.length()) {
            if (i < ansiString.length() - 1
                    && ansiString.charAt(i) == '\033'
                    && ansiString.charAt(i + 1) == '[') {
                // Flush current text as a span
                if (!currentText.isEmpty()) {
                    spans.add(Span.styled(currentText.toString(), currentStyle));
                    currentText.setLength(0);
                }
                // Parse SGR sequence
                int end = ansiString.indexOf('m', i + 2);
                if (end < 0) {
                    // Malformed sequence, output as text
                    currentText.append(ansiString.charAt(i));
                    i++;
                    continue;
                }
                var params = ansiString.substring(i + 2, end);
                currentStyle = applySgr(currentStyle, params);
                i = end + 1;
            } else if (ansiString.charAt(i) == '\n') {
                // Flush span and line
                if (!currentText.isEmpty()) {
                    spans.add(Span.styled(currentText.toString(), currentStyle));
                    currentText.setLength(0);
                }
                lines.add(Line.from(List.copyOf(spans)));
                spans.clear();
                i++;
            } else {
                currentText.append(ansiString.charAt(i));
                i++;
            }
        }
        // Flush remaining
        if (!currentText.isEmpty()) {
            spans.add(Span.styled(currentText.toString(), currentStyle));
        }
        if (!spans.isEmpty()) {
            lines.add(Line.from(List.copyOf(spans)));
        }
        if (lines.isEmpty()) {
            lines.add(Line.empty());
        }
        return Text.from(lines);
    }

    static Style applySgr(Style style, String params) {
        if (params.isEmpty()) {
            return Style.EMPTY;
        }
        var parts = params.split(";");
        int idx = 0;
        while (idx < parts.length) {
            int code;
            try {
                code = Integer.parseInt(parts[idx]);
            } catch (NumberFormatException e) {
                idx++;
                continue;
            }
            switch (code) {
                case 0 -> style = Style.EMPTY;
                case 1 -> style = style.bold();
                case 3 -> style = style.italic();
                case 38 -> {
                    // Foreground color: 38;2;r;g;b
                    if (idx + 4 < parts.length && "2".equals(parts[idx + 1])) {
                        try {
                            int r = Integer.parseInt(parts[idx + 2]);
                            int g = Integer.parseInt(parts[idx + 3]);
                            int b = Integer.parseInt(parts[idx + 4]);
                            style = style.fg(Color.rgb(r, g, b));
                        } catch (NumberFormatException e) {
                            // skip malformed
                        }
                        idx += 4;
                    }
                }
                default -> {} // ignore unsupported codes
            }
            idx++;
        }
        return style;
    }
}
