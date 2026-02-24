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
                text(" \u24CC").fg(Color.RED).bold().fit(),
                text("\u2468").fg(Color.YELLOW).bold().fit(),
                text("\u24C8").fg(Color.GREEN).bold().fit(),
                text("  ").fit(),
                text(ctx.filename()).cyan().fit())
                .length(1);
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
