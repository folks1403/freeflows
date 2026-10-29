package com.freeflows.android.test;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ProtocolTest {
    @Test public void invitationRemovesCreatorSecretAndRetainsPort() {
        RoomLink link = new RoomLink(" https://example.org:8443/arena?invite=a%2Bb#control=secret ");
        assertEquals("https://example.org:8443", link.base);
        assertEquals("a+b", link.invite);
        assertEquals("secret", link.control);
        assertEquals("https://example.org:8443/?invite=a%2Bb", link.invitation());
    }
    @Test public void refusesUnencryptedOrMissingInvites() {
        for (String value : new String[]{"http://example.org/?invite=x", "https://example.org/", "https://user:pass@example.org/?invite=x"}) {
            try { new RoomLink(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void wavIsMono16kPcmLittleEndian() {
        byte[] wav = SpeechSegments.wav(new byte[]{1, 2, 3, 4});
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(48, wav.length); assertEquals(40, b.getInt(4));
        assertEquals(1, b.getShort(22)); assertEquals(16000, b.getInt(24));
        assertEquals(16, b.getShort(34)); assertEquals(4, b.getInt(40));
    }
    @Test public void silenceDoesNotBecomeSpeechAndPauseEndsSpeech() {
        SpeechSegments s = new SpeechSegments();
        assertNull(s.consume(new byte[44800], "turn"));
        byte[] voice = new byte[9600];
        for (int i = 1; i < voice.length; i += 2) voice[i] = 20;
        assertNull(s.consume(voice, "turn"));
        SpeechSegments.Segment result = s.consume(new byte[44800], "turn");
        assertNotNull(result); assertTrue(result.ended()); assertEquals(0, result.index());
        assertNull(s.flush(false));
    }
}
