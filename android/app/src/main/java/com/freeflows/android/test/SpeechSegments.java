package com.freeflows.android.test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class SpeechSegments {
    record Segment(String turnId, int index, boolean ended, byte[] wav) {}
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private String turn;
    private int index, speech, silence;
    synchronized Segment consume(byte[] data, String turnId) {
        if (!turnId.equals(turn)) { pcm.reset(); speech = silence = index = 0; turn = turnId; }
        pcm.write(data, 0, data.length);
        double sum = 0;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 2) { double v = b.getShort() / 32768.0; sum += v * v; }
        int count = data.length / 2;
        if (Math.sqrt(sum / Math.max(1, count)) > .018) { speech += count; silence = 0; }
        else silence += count;
        boolean ended = speech >= 4800 && silence >= 22400;
        return ended || pcm.size() >= 640000 ? flush(ended) : null;
    }
    synchronized Segment flush(boolean ended) {
        Segment result = speech >= 4800 && turn != null ? new Segment(turn, index++, ended, wav(pcm.toByteArray())) : null;
        pcm.reset(); speech = silence = 0;
        return result;
    }
    static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1);
        b.putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
