package dev.zeroone.bikethere;

record RideColors(boolean dark, int paper, int panel, int ink, int muted, int line,
                  int accent, int onAccent, int violet, int violetInk) {
    static final int TRACK = 0xff242034;
    static final int SIGNAL = 0xffdafa5e;
    static final int ON_TRACK = 0xfff9f5ff;

    static RideColors forDark(boolean dark) {
        return dark
                ? new RideColors(true, 0xff17151f, 0xff262232, 0xfff7f1fc, 0xffc3b9cf,
                        0xff41384f, SIGNAL, 0xff222617, 0xffc7a9fa, 0xff271540)
                : new RideColors(false, 0xfff6f0e6, 0xfffffaf2, 0xff241c32, 0xff61546d,
                        0xffd9cedc, 0xff463071, 0xffffffff, 0xffe0cefb, 0xff32184d);
    }
}
