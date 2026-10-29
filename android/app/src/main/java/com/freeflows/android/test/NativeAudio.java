package com.freeflows.android.test;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class NativeAudio {
    private final AudioManager manager;
    private final ConcurrentHashMap<String, Speaker> speakers = new ConcurrentHashMap<>();
    private AudioRecord recorder;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private Thread capture;
    private volatile boolean running;
    private int previousMode;
    NativeAudio(Context context) { manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }
    @SuppressLint("MissingPermission")
    void start(boolean record, Consumer<byte[]> onPCM, Consumer<String> onError) {
        stop(); previousMode = manager.getMode(); manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        running = true;
        if (!record) return;
        try {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Microfone não suporta PCM 16 kHz.");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 6400));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microfone indisponível.");
            if (AcousticEchoCanceler.isAvailable()) { echo = AcousticEchoCanceler.create(recorder.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { noise = NoiseSuppressor.create(recorder.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
            AudioRecord source = recorder; source.startRecording();
            capture = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                byte[] buffer = new byte[640];
                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        int size = source.read(buffer, 0, buffer.length);
                        if (size < 0) throw new IllegalStateException("Falha na captura. Retome o áudio.");
                        if (size > 0 && size % 2 == 0) onPCM.accept(Arrays.copyOf(buffer, size));
                    }
                } catch (Exception e) { if (running) onError.accept("Microfone interrompido. Toque em Retomar áudio."); }
            }, "FreeFlows microphone"); capture.start();
        } catch (RuntimeException e) { stop(); throw e; }
    }
    void receive(String sender, byte[] pcm) {
        if (!running || pcm.length == 0 || pcm.length % 2 != 0) return;
        Speaker speaker = speakers.get(sender);
        if (speaker == null) synchronized (this) {
            if (!running) return;
            speaker = speakers.get(sender);
            if (speaker == null && speakers.size() < 2) {
                try { speaker = new Speaker(); speakers.put(sender, speaker); }
                catch (RuntimeException ignored) { return; }
            }
        }
        if (speaker != null) speaker.queue.offer(pcm);
    }
    synchronized void stop() {
        boolean wasRunning = running; running = false;
        if (capture != null) capture.interrupt();
        if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) {} }
        if (capture != null) { try { capture.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } capture = null; }
        if (echo != null) { echo.release(); echo = null; }
        if (noise != null) { noise.release(); noise = null; }
        if (recorder != null) { recorder.release(); recorder = null; }
        speakers.values().forEach(Speaker::close); speakers.clear();
        if (wasRunning) manager.setMode(previousMode);
    }
    private static final class Speaker {
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(20);
        final AudioTrack track;
        final Thread player;
        volatile boolean alive = true;
        Speaker() {
            int min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min * 2, 6400))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
            player = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                try { while (alive) { byte[] data = queue.take(); track.write(data, 0, data.length); } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (RuntimeException ignored) { }
            }, "FreeFlows playback"); player.start();
        }
        void close() {
            alive = false; player.interrupt();
            try { track.pause(); track.flush(); player.join(500); } catch (Exception ignored) { }
            track.release(); queue.clear();
        }
    }
}
