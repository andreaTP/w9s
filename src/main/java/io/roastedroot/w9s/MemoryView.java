package io.roastedroot.w9s;

import static dev.tamboui.toolkit.Toolkit.*;
import com.dylibso.chicory.runtime.Memory;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MemoryView implements View {

    private static final String[] TYPED_VALUE_TYPES = {"i32", "i64", "f32", "f64"};
    private int memViewAddress = 0;
    private boolean inMemGoto = false;
    private String memGotoInput = "";
    private boolean inMemWriteString = false;
    private boolean memWriteAddrPhase = true;
    private String memWriteAddr = "";
    private String memWriteStringInput = "";
    private boolean memWriteNullTerm = false;
    private boolean inMemWriteTyped = false;
    private int memWriteTypedPhase = 0;
    private String memWriteTypedAddr = "";
    private int memWriteTypedTypeIdx = 0;
    private String memWriteTypedValue = "";
    private String memStatusMessage;

    @Override
    public EventResult handleKey(KeyEvent key, ViewContext ctx) {
        if (key.isQuit()) { ctx.navigateTo(new ViewTransition.Quit()); return EventResult.HANDLED; }
        Memory mem = ctx.instanceManager().memory();
        int maxAddr = mem.pages() * 65536;

        // Sub-mode: goto
        if (inMemGoto) {
            if (key.isCancel()) { inMemGoto = false; memGotoInput = ""; return EventResult.HANDLED; }
            if (key.isConfirm()) {
                if (!memGotoInput.isEmpty()) {
                    try {
                        int addr = Integer.parseUnsignedInt(memGotoInput);
                        if (addr >= 0 && addr < maxAddr) { memViewAddress = (addr / 8) * 8; memStatusMessage = null; }
                        else { memStatusMessage = "Address out of range"; }
                    } catch (NumberFormatException e) { memStatusMessage = "Invalid address"; }
                }
                inMemGoto = false; memGotoInput = ""; return EventResult.HANDLED;
            }
            if (key.isDeleteBackward() && !memGotoInput.isEmpty()) { memGotoInput = memGotoInput.substring(0, memGotoInput.length() - 1); return EventResult.HANDLED; }
            if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) { char c = key.character(); if (c >= '0' && c <= '9') memGotoInput += c; return EventResult.HANDLED; }
            return EventResult.HANDLED;
        }
        // Sub-mode: write string
        if (inMemWriteString) {
            if (key.isCancel()) { inMemWriteString = false; return EventResult.HANDLED; }
            if (memWriteAddrPhase) {
                if (key.isConfirm() && !memWriteAddr.isEmpty()) { memWriteAddrPhase = false; return EventResult.HANDLED; }
                if (key.isDeleteBackward() && !memWriteAddr.isEmpty()) { memWriteAddr = memWriteAddr.substring(0, memWriteAddr.length() - 1); return EventResult.HANDLED; }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) { char c = key.character(); if (c >= '0' && c <= '9') memWriteAddr += c; return EventResult.HANDLED; }
            } else {
                if (key.isChar('t') && key.hasCtrl()) { memWriteNullTerm = !memWriteNullTerm; return EventResult.HANDLED; }
                if (key.isConfirm()) {
                    if (!memWriteStringInput.isEmpty()) {
                        try {
                            int addr = Integer.parseUnsignedInt(memWriteAddr);
                            byte[] strBytes = memWriteStringInput.getBytes(StandardCharsets.UTF_8);
                            byte[] bytes;
                            if (memWriteNullTerm) { bytes = new byte[strBytes.length + 1]; System.arraycopy(strBytes, 0, bytes, 0, strBytes.length); }
                            else { bytes = strBytes; }
                            mem.write(addr, bytes);
                            memViewAddress = (addr / 8) * 8;
                            memStatusMessage = "Wrote " + bytes.length + " bytes at " + addr;
                        } catch (Exception e) { memStatusMessage = "Write failed: " + e.getMessage(); }
                    }
                    inMemWriteString = false; return EventResult.HANDLED;
                }
                if (key.isDeleteBackward() && !memWriteStringInput.isEmpty()) { memWriteStringInput = memWriteStringInput.substring(0, memWriteStringInput.length() - 1); return EventResult.HANDLED; }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) { memWriteStringInput += key.character(); return EventResult.HANDLED; }
            }
            return EventResult.HANDLED;
        }
        // Sub-mode: write typed
        if (inMemWriteTyped) {
            if (key.isCancel()) { inMemWriteTyped = false; return EventResult.HANDLED; }
            if (memWriteTypedPhase == 0) {
                if (key.isConfirm() && !memWriteTypedAddr.isEmpty()) { memWriteTypedPhase = 1; return EventResult.HANDLED; }
                if (key.isDeleteBackward() && !memWriteTypedAddr.isEmpty()) { memWriteTypedAddr = memWriteTypedAddr.substring(0, memWriteTypedAddr.length() - 1); return EventResult.HANDLED; }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) { char c = key.character(); if (c >= '0' && c <= '9') memWriteTypedAddr += c; return EventResult.HANDLED; }
            } else if (memWriteTypedPhase == 1) {
                if (key.isLeft()) { memWriteTypedTypeIdx = (memWriteTypedTypeIdx - 1 + TYPED_VALUE_TYPES.length) % TYPED_VALUE_TYPES.length; return EventResult.HANDLED; }
                if (key.isRight()) { memWriteTypedTypeIdx = (memWriteTypedTypeIdx + 1) % TYPED_VALUE_TYPES.length; return EventResult.HANDLED; }
                if (key.isConfirm()) { memWriteTypedPhase = 2; return EventResult.HANDLED; }
            } else {
                if (key.isConfirm()) {
                    if (!memWriteTypedValue.isEmpty()) {
                        try {
                            int addr = Integer.parseUnsignedInt(memWriteTypedAddr);
                            byte[] bytes = ParamUtils.encodeTypedValue(TYPED_VALUE_TYPES[memWriteTypedTypeIdx], memWriteTypedValue);
                            mem.write(addr, bytes);
                            memViewAddress = (addr / 8) * 8;
                            memStatusMessage = "Wrote " + TYPED_VALUE_TYPES[memWriteTypedTypeIdx] + " (" + bytes.length + " bytes) at " + addr;
                        } catch (Exception e) { memStatusMessage = "Write failed: " + e.getMessage(); }
                    }
                    inMemWriteTyped = false; return EventResult.HANDLED;
                }
                if (key.isDeleteBackward() && !memWriteTypedValue.isEmpty()) { memWriteTypedValue = memWriteTypedValue.substring(0, memWriteTypedValue.length() - 1); return EventResult.HANDLED; }
                if (key.code() == KeyCode.CHAR && !key.hasCtrl() && !key.hasAlt()) { memWriteTypedValue += key.character(); return EventResult.HANDLED; }
            }
            return EventResult.HANDLED;
        }
        // Main memory view keys
        if (key.isCancel() || key.isLeft()) { ctx.navigateTo(new ViewTransition.ToDetailView()); return EventResult.HANDLED; }
        if (key.isUp()) { memViewAddress = Math.max(0, memViewAddress - 8); return EventResult.HANDLED; }
        if (key.isDown()) { if (memViewAddress + 8 < maxAddr) memViewAddress += 8; return EventResult.HANDLED; }
        if (key.isPageUp()) { memViewAddress = Math.max(0, memViewAddress - ViewLayout.PAGE_SIZE * 8); return EventResult.HANDLED; }
        if (key.isPageDown()) { memViewAddress = Math.min(maxAddr - 8, memViewAddress + ViewLayout.PAGE_SIZE * 8); if (memViewAddress < 0) memViewAddress = 0; return EventResult.HANDLED; }
        if (key.isHome()) { memViewAddress = 0; return EventResult.HANDLED; }
        if (key.isEnd()) { memViewAddress = Math.max(0, maxAddr - ViewLayout.PAGE_SIZE * 8); return EventResult.HANDLED; }
        if (key.isChar('g')) { inMemGoto = true; memGotoInput = ""; return EventResult.HANDLED; }
        if (key.isChar('w')) { inMemWriteString = true; memWriteAddrPhase = true; memWriteAddr = ""; memWriteStringInput = ""; memWriteNullTerm = false; return EventResult.HANDLED; }
        if (key.isChar('e')) { inMemWriteTyped = true; memWriteTypedPhase = 0; memWriteTypedAddr = ""; memWriteTypedTypeIdx = 0; memWriteTypedValue = ""; return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    @Override
    public Element render(ViewContext ctx) {
        Memory mem = ctx.instanceManager().memory();
        int totalBytes = mem.pages() * 65536;
        int bytesPerLine = 8;

        String promptText = null;
        if (inMemGoto) promptText = String.format("Go to address: %s_", memGotoInput);
        else if (inMemWriteString) {
            if (memWriteAddrPhase) promptText = String.format("Write string at address: %s_", memWriteAddr);
            else promptText = String.format("Write string at %s [%s] (Ctrl+T to toggle): %s_", memWriteAddr, memWriteNullTerm ? "null-terminated" : "raw", memWriteStringInput);
        } else if (inMemWriteTyped) {
            if (memWriteTypedPhase == 0) promptText = String.format("Write value at address: %s_", memWriteTypedAddr);
            else if (memWriteTypedPhase == 1) promptText = String.format("Type: \u25c4 %s \u25ba (\u2190/\u2192 to change, Enter to confirm)", TYPED_VALUE_TYPES[memWriteTypedTypeIdx]);
            else promptText = String.format("Write %s at %s: %s_", TYPED_VALUE_TYPES[memWriteTypedTypeIdx], memWriteTypedAddr, memWriteTypedValue);
        }
        if (memStatusMessage != null) promptText = (promptText != null ? promptText + "\n" : "") + memStatusMessage;

        int visibleLines = ViewLayout.PAGE_SIZE + 5;
        int windowSize = Math.min(visibleLines * bytesPerLine, totalBytes - memViewAddress);
        if (windowSize <= 0) windowSize = 0;

        var sb = new StringBuilder();
        sb.append(String.format("Address   00 01 02 03 04 05 06 07  ASCII%n"));
        if (windowSize > 0) {
            byte[] data = mem.readBytes(memViewAddress, windowSize);
            for (int i = 0; i < data.length; i += bytesPerLine) {
                int addr = memViewAddress + i;
                sb.append(String.format("%08x  ", addr));
                int lineLen = Math.min(bytesPerLine, data.length - i);
                for (int j = 0; j < bytesPerLine; j++) {
                    if (j < lineLen) sb.append(String.format("%02X ", data[i + j] & 0xFF));
                    else sb.append("   ");
                }
                sb.append(' ');
                for (int j = 0; j < lineLen; j++) { int b = data[i + j] & 0xFF; sb.append(b >= 0x20 && b < 0x7f ? (char) b : '.'); }
                sb.append('\n');
            }
        }
        var memTitle = String.format("Memory (%d pages, %d bytes)", mem.pages(), totalBytes);

        Element helpContent;
        if (inMemGoto || inMemWriteString || inMemWriteTyped) {
            helpContent = row(text(" Enter").cyan().fit(), text(" confirm  ").dim().fit(), text("ESC").cyan().fit(), text(" cancel").dim().fit());
        } else {
            helpContent = row(text(" g").cyan().fit(), text(" goto  ").dim().fit(), text("w").cyan().fit(), text(" write-string  ").dim().fit(),
                    text("e").cyan().fit(), text(" write-value  ").dim().fit(), text("\u2191\u2193").cyan().fit(), text(" scroll  ").dim().fit(),
                    text("ESC/\u2190").cyan().fit(), text(" back").dim().fit());
        }

        var memLines = new ArrayList<Line>();
        for (var line : sb.toString().split("\n", -1)) memLines.add(Line.from(List.of(Span.styled(line, Style.EMPTY))));
        var memText = Text.from(memLines);
        Element panelContent;
        if (promptText != null) {
            var promptLines = new ArrayList<Line>();
            for (var line : promptText.split("\n", -1)) promptLines.add(Line.from(List.of(Span.styled(line, Style.EMPTY.fg(Color.YELLOW)))));
            int promptHeight = promptLines.size() + 1; // +1 for separator
            panelContent = column(
                    richText(memText).overflow(Overflow.CLIP).fill(),
                    richText(Text.from(promptLines)).fit().length(promptHeight)).fill();
        } else {
            panelContent = richText(memText).overflow(Overflow.CLIP).fill();
        }
        var contentPanel = panel(panelContent).title(memTitle).rounded().borderColor(Color.CYAN).fill(1);
        return ViewLayout.layout(ctx, contentPanel, helpContent);
    }
}
