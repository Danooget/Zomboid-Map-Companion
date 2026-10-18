package livemap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** User-controlled render budgets. Does not change the size of the on-screen window. */
public final class LiveWebMapPerformance {
    public enum Preset {
        ECO("Eco", 30, 1280, 720),
        BALANCED("Balanced", 30, 1600, 900),
        SMOOTH("High", 30, 1920, 1080),
        DETAIL("Ultra", 30, 3840, 2160);

        final String title;
        final int fps;
        final int maxWidth;
        final int maxHeight;

        Preset(String title, int fps, int maxWidth, int maxHeight) {
            this.title = title;
            this.fps = fps;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }
    }

    private static final Preset[] PRESETS = Preset.values();
    private static final Path PREFERENCE = Paths.get(System.getProperty("user.home"),
            "Zomboid", "LiveWebMap", "render-mode.txt");
    private static volatile Preset selected = readPreference();

    private LiveWebMapPerformance() { }
    public static int count() { return PRESETS.length; }
    public static int selectedIndex() { return selected.ordinal(); }
    public static String nameAt(int index) { return PRESETS[index].title; }
    public static String descriptionAt(int index) {
        Preset p = PRESETS[index];
        // Short quality labels fit the existing 100px menu without clipping.
        return switch (p) {
            case ECO -> "Eco 720p";
            case BALANCED -> "Bal 900p";
            case SMOOTH -> "High 1080p";
            case DETAIL -> "Ultra 4K";
        };
    }
    public static String currentName() { return selected.title; }
    public static int targetFps() { return selected.fps; }

    private static Preset readPreference() {
        try {
            if (Files.isRegularFile(PREFERENCE)) {
                String value = Files.readString(PREFERENCE, StandardCharsets.UTF_8).trim();
                for (Preset p : PRESETS) if (p.name().equalsIgnoreCase(value)) return p;
            }
        } catch (Exception e) {
            System.err.println("[LiveWebMap] Could not read render preference: " + e);
        }
        return Preset.BALANCED;
    }

    public static void select(int index) {
        if (index < 0 || index >= PRESETS.length) return;
        Preset next = PRESETS[index];
        selected = next;
        try {
            Files.createDirectories(PREFERENCE.getParent());
            Files.writeString(PREFERENCE, next.name(), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            System.err.println("[LiveWebMap] Render preference will not persist: " + e);
        }
        System.out.println("[LiveWebMap] Render mode: " + next.title
                + " (up to " + next.fps + " FPS; browser capped at "
                + next.maxWidth + "x" + next.maxHeight + ")");
    }

    /** Keep webpage aspect ratio, cap expensive browser pixels, never upscale small panels. */
    public static int[] viewport(double contentWidth, double contentHeight) {
        Preset p = selected;
        double requestedW = Math.max(320.0, contentWidth);
        double requestedH = Math.max(240.0, contentHeight);
        double scale = Math.min(1.0,
                Math.min(p.maxWidth / requestedW, p.maxHeight / requestedH));
        int width = (int) Math.round(requestedW * scale);
        int height = (int) Math.round(requestedH * scale);
        return new int[] {Math.max(320, Math.min(3840, width)),
                Math.max(240, Math.min(2160, height))};
    }
}
