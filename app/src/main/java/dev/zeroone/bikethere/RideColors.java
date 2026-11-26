package dev.zeroone.bikethere;

record RideColors(boolean dark, int paper, int panel, int ink, int muted, int line, int accent) {
    static final int TRACK = 0xff20211f;
    static final int SIGNAL = 0xffdafa5e;
    static final int ON_TRACK = 0xfff2f1e9;
    static final int TRACK_MUTED = 0xffc0c1b6;

    static RideColors forDark(boolean dark) {
        return dark
                ? new RideColors(true, 0xff191a18, 0xff232521, 0xfff2f1e9, 0xffb7b9ae, 0xff4b4e45, SIGNAL)
                : new RideColors(false, 0xffefeee6, 0xffe5e5da, 0xff242620, 0xff575b50, 0xffbdbfb2, 0xff46570f);
    }
}
