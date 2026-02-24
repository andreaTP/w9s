package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TableElement;

final class ViewLayout {

    static final int PAGE_SIZE = 20;

    private ViewLayout() {}

    static Element buildTitleBar(ViewContext ctx) {
        return row(
                column(
                        text(" \u257b \u257b \u250f\u2501\u2513 \u250f\u2501\u2578")
                                .fg(Color.LIGHT_CYAN).bold(),
                        text(" \u2503\u257b\u2503 \u2517\u2501\u252b \u2517\u2501\u2513")
                                .cyan().bold(),
                        text(" \u2517\u253b\u251b \u257a\u2501\u251b \u257a\u2501\u251b")
                                .fg(Color.LIGHT_BLUE).bold())
                        .length(12),
                panel().title(ctx.filename()).rounded().borderColor(Color.CYAN).fill(1))
                .length(3);
    }

    static TableElement buildSectionsTable(ViewContext ctx) {
        var sections = table()
                .header("Section", "Count")
                .widths(length(12), length(8))
                .columnSpacing(1)
                .state(ctx.sectionTableState())
                .highlightSymbol("\u25b6 ")
                .highlightStyle(Style.EMPTY.bg(Color.LIGHT_BLUE).fg(Color.BLACK));
        for (var row : ctx.sectionRows()) {
            sections.row(row);
        }
        return sections;
    }

    static Element layout(ViewContext ctx, Element contentPanel, Element helpContent) {
        return layout(ctx, contentPanel, helpContent, Color.DARK_GRAY);
    }

    static Element layout(ViewContext ctx, Element contentPanel, Element helpContent, Color sectionsBorderColor) {
        var sections = buildSectionsTable(ctx);
        return column(
                        buildTitleBar(ctx),
                        row(
                                panel(() -> sections)
                                        .title("Sections")
                                        .bottomTitle("\u2191\u2193 navigate")
                                        .rounded()
                                        .borderColor(sectionsBorderColor)
                                        .length(28),
                                contentPanel)
                                .fill(),
                        helpBar(helpContent))
                .fill();
    }

    static Element helpBar(Element content) {
        return panel(content)
                .rounded()
                .borderColor(Color.DARK_GRAY)
                .length(3);
    }
}
