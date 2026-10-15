package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelSelectionTest {
    @Test public void remoteWrapsAtBothEnds() {
        assertEquals(0, ChannelSelection.next(9, 1, 10));
        assertEquals(9, ChannelSelection.next(0, -1, 10));
        assertEquals(5, ChannelSelection.next(4, 1, 10));
        assertEquals(3, ChannelSelection.next(4, -1, 10));
    }
    @Test public void holdingRemoteCanContinueThroughSeveralCycles() {
        int selected = 0;
        for (int i = 0; i < 23; i++) selected = ChannelSelection.next(selected, 1, 5);
        assertEquals(3, selected);
        for (int i = 0; i < 23; i++) selected = ChannelSelection.next(selected, -1, 5);
        assertEquals(0, selected);
    }
    @Test public void emptyAndSingleChannelGroupsRemainSelectable() {
        assertEquals(-1, ChannelSelection.next(-1, 1, 0));
        assertEquals(-1, ChannelSelection.next(0, -1, 0));
        assertEquals(0, ChannelSelection.next(0, -1, 1));
        assertEquals(0, ChannelSelection.next(0, 1, 1));
    }
    @Test public void missingSelectionStartsAtTheDirectionalEdge() {
        assertEquals(0, ChannelSelection.next(-1, 1, 4));
        assertEquals(3, ChannelSelection.next(-1, -1, 4));
        assertEquals(0, ChannelSelection.next(10, 1, 4));
        assertEquals(3, ChannelSelection.next(10, -1, 4));
    }
}
