package SpaceUtils.rangedisplay.Gui;

public final class Theme {
    private Theme() {
    }

    public static int argb(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static final int SAFE_FILL = argb(70, 220, 100, 95);
    public static final int SAFE_EDGE = argb(90, 255, 120, 255);

    public static final int DANGER_FILL = argb(255, 70, 80, 105);
    public static final int DANGER_EDGE = argb(255, 80, 90, 255);
}
