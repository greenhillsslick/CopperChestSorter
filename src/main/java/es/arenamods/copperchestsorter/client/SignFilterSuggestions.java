package es.arenamods.copperchestsorter.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;

import java.lang.reflect.Field;
import java.util.Locale;

/** Adds clickable exact/family choices to Minecraft's normal sign editor. */
public final class SignFilterSuggestions {
    private static final int PANEL_HEIGHT = 58;
    private static final int EXACT_COLOR = 0xFF55DD88;
    private static final int FAMILY_COLOR = 0xFFFFC247;

    private SignFilterSuggestions() {}

    public static void render(Object screen, GuiGraphicsExtractor graphics, int width, int height, Font font) {
        Selection selection = selection(screen);
        if (selection == null) return;

        Panel panel = panel(width, height);
        graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + PANEL_HEIGHT, 0xE0101018);
        graphics.outline(panel.x, panel.y, panel.width, PANEL_HEIGHT, 0xFFB87535);

        int middle = panel.x + panel.width / 2;
        String exact = "Exacto: " + selection.name;
        String family = "Variantes: " + selection.name + "*";
        graphics.text(font, exact, panel.x + 9, panel.y + 7, EXACT_COLOR);
        graphics.text(font, family, middle + 5, panel.y + 7, FAMILY_COLOR);
        graphics.text(font, "Verde = solo este objeto/bloque", panel.x + 9, panel.y + 21, EXACT_COLOR);
        graphics.text(font, "Dorado = familia y variantes (*)", middle + 5, panel.y + 21, FAMILY_COLOR);
        graphics.text(font, "Haz clic en una opción para aplicarla a esta línea", panel.x + 9, panel.y + 40, 0xFFE7E7E7);
    }

    /** Returns true when a click selected one of the sign-filter choices. */
    public static boolean handleClick(Object screen, double mouseX, double mouseY, int button, int width, int height) {
        if (button != 0) return false;
        Selection selection = selection(screen);
        if (selection == null) return false;

        Panel panel = panel(width, height);
        if (mouseY < panel.y + 3 || mouseY > panel.y + 34) return false;
        if (mouseX >= panel.x && mouseX < panel.x + panel.width / 2) {
            apply(selection, false);
            return true;
        }
        if (mouseX >= panel.x + panel.width / 2 && mouseX <= panel.x + panel.width) {
            apply(selection, true);
            return true;
        }
        return false;
    }

    private static Panel panel(int width, int height) {
        int panelWidth = Math.min(440, Math.max(220, width - 16));
        return new Panel((width - panelWidth) / 2, Math.max(8, Math.min(12, height - PANEL_HEIGHT - 4)), panelWidth);
    }

    private static Selection selection(Object screen) {
        if (!isSignEditScreen(screen)) return null;
        String[] messages = findMessages(screen);
        if (messages == null) return null;

        int row = findCurrentRow(screen);
        if (row < 0 || row >= messages.length) return null;
        String line = messages[row];
        if (line == null || line.isBlank()) return null;

        String token = line.substring(line.lastIndexOf(',') + 1).trim();
        if (token.startsWith("(") && token.endsWith(")") && token.length() > 2) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.endsWith("*")) token = token.substring(0, token.length() - 1).trim();
        if (token.isEmpty() || token.startsWith("#")) return null;
        return new Selection(messages, row, token);
    }

    private static boolean isSignEditScreen(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals("SignEditScreen")) return true;
        }
        return false;
    }

    private static String[] findMessages(Object screen) {
        Field fallback = null;
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String[].class) continue;
                if (field.getName().toLowerCase(Locale.ROOT).contains("message")) {
                    String[] messages = readMessages(screen, field);
                    if (messages != null) return messages;
                }
                if (fallback == null) fallback = field;
            }
        }
        return fallback == null ? null : readMessages(screen, fallback);
    }

    private static String[] readMessages(Object screen, Field field) {
        try {
            field.setAccessible(true);
            Object value = field.get(screen);
            return value instanceof String[] messages && messages.length == 4 ? messages : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static int findCurrentRow(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (field.getType() != int.class || !(name.contains("row") || name.contains("line"))) continue;
                try {
                    field.setAccessible(true);
                    int row = field.getInt(screen);
                    if (row >= 0 && row < 4) return row;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try another row/line field, then fall back to the first sign line.
                }
            }
        }
        return 0;
    }

    private static void apply(Selection selection, boolean variants) {
        String line = selection.messages[selection.row];
        int lastComma = line.lastIndexOf(',');
        String prefix = line.substring(0, lastComma + 1);
        String segment = line.substring(lastComma + 1);
        String leading = segment.substring(0, segment.length() - segment.stripLeading().length());
        String remainder = segment.stripLeading();
        String trailing = remainder.substring(remainder.stripTrailing().length());
        String name = remainder.strip();
        if (name.startsWith("(") && name.endsWith(")") && name.length() > 2) {
            name = name.substring(1, name.length() - 1).trim();
        }
        if (name.endsWith("*")) name = name.substring(0, name.length() - 1).trim();
        selection.messages[selection.row] = prefix + leading + name + (variants ? "*" : "") + trailing;
    }

    private record Selection(String[] messages, int row, String name) {}
    private record Panel(int x, int y, int width) {}
}
