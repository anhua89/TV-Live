package cn.anhuatv.live.core;

/** Move only the selection. Playback is still committed by the remote OK key. */
public final class ChannelSelection {
    private ChannelSelection() {}

    public static int next(int current, int delta, int count) {
        if (count <= 0) return -1;
        if (current < 0 || current >= count) return delta < 0 ? count - 1 : 0;
        long position = ((long) current + delta) % count;
        return (int) (position < 0 ? position + count : position);
    }
}
