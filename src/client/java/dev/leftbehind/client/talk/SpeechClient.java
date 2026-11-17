package dev.leftbehind.client.talk;

import dev.leftbehind.LeftBehind;
import dev.leftbehind.talk.SpeechPayload;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Shows what Steve and the others say: typed out above their heads, and as a subtitle box
 * just above the hotbar, so it can't get lost in chat.
 */
public final class SpeechClient {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(LeftBehind.MOD_ID, "speech");
    /** Characters typed out per tick. */
    private static final float TYPING = 1.6F;
    private static final int BUBBLE_WIDTH = 180;

    private record Line(int entityId, String speaker, int color, String text, int start, int ticks, int style) {
        int end() {
            return start + ticks;
        }
    }

    private static final Map<Integer, Line> BUBBLES = new HashMap<>();
    private static final ArrayDeque<Line> SUBTITLES = new ArrayDeque<>();
    private static int age;

    private SpeechClient() {}

    public static void receive(SpeechPayload payload) {
        Line line = new Line(payload.entityId(), payload.speaker(), payload.color(), payload.text(), age, payload.ticks(), payload.style());
        if (payload.style() == SpeechPayload.SPOKEN && payload.entityId() >= 0) BUBBLES.put(payload.entityId(), line);
        SUBTITLES.addLast(line);
        while (SUBTITLES.size() > 3) SUBTITLES.removeFirst();
    }

    public static void reset() {
        BUBBLES.clear();
        SUBTITLES.clear();
    }

    public static void tick(Minecraft client) {
        if (client.level == null) {
            reset();
            return;
        }
        if (client.isPaused()) return;
        age++;
        BUBBLES.values().removeIf(line -> age > line.end());
        SUBTITLES.removeIf(line -> age > line.end());
    }

    private static int shown(Line line, float partial) {
        return Math.min(line.text().length(), (int) ((age + partial - line.start()) * TYPING) + 1);
    }

    /** Splits the whole line first, then reveals it, so words don't jump between rows while it types. */
    private static List<String> typed(Font font, Line line, int width, float partial) {
        List<String> rows = new ArrayList<>();
        int left = shown(line, partial);
        for (FormattedText part : font.getSplitter().splitLines(line.text(), width, Style.EMPTY)) {
            if (left <= 0) break;
            String row = part.getString();
            rows.add(row.substring(0, Math.min(row.length(), left)));
            left -= row.length() + 1;
        }
        return rows;
    }

    /** The rows to draw above this entity's head right now, top row first, or null if it isn't talking. */
    public static @Nullable List<Component> bubble(int entityId, float partial) {
        Line line = BUBBLES.get(entityId);
        if (line == null) return null;
        List<Component> rows = new ArrayList<>();
        for (String row : typed(Minecraft.getInstance().font, line, BUBBLE_WIDTH, partial)) rows.add(Component.literal(row));
        return rows;
    }

    /** The subtitle box above the hotbar. */
    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (SUBTITLES.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()) return;
        Font font = client.font;
        float partial = delta.getGameTimeDeltaPartialTick(true);
        int screenW = graphics.guiWidth();
        int width = Math.min(320, screenW - 40);
        int bottom = graphics.guiHeight() - 72;
        // Newest at the bottom, older lines above it.
        Iterator<Line> it = SUBTITLES.descendingIterator();
        while (it.hasNext()) {
            Line line = it.next();
            Component name = Component.literal(line.speaker() + ": ").withStyle(s -> s.withColor(TextColor.fromRgb(line.color())));
            int nameW = font.width(name);
            List<String> rows = typed(font, line, width - nameW, partial);
            if (rows.isEmpty()) continue;
            int rowsW = 0;
            for (String row : rows) rowsW = Math.max(rowsW, font.width(row));
            int boxW = nameW + rowsW;
            int boxH = rows.size() * 10;
            int x = (screenW - boxW) / 2;
            int y = bottom - boxH;
            float fade = Mth.clamp((line.end() - age - partial) / 12.0F, 0.0F, 1.0F);
            if (fade <= 0.02F) continue;
            graphics.fill(x - 4, y - 3, x + boxW + 4, y + boxH + 1, ARGB.color(0.55F * fade, 0x000000));
            graphics.text(font, name, x, y, ARGB.white(fade));
            int textColor = line.style() == SpeechPayload.WHISPER ? ARGB.color(fade, 0xB8B8B8) : ARGB.white(fade);
            for (int i = 0; i < rows.size(); i++) {
                graphics.text(font, rows.get(i), x + nameW, y + i * 10, textColor);
            }
            bottom = y - 6;
        }
    }
}
