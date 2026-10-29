package com.freeflows.android.test;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService upload = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final ArrayDeque<SpeechSegments.Segment> pending = new ArrayDeque<>();
    private final SpeechSegments segments = new SpeechSegments();
    private volatile RoomLink link;
    private volatile String token = "", id = "";
    private volatile JSONObject room;
    private volatile WebSocket socket;
    private volatile boolean audioReady, foreground, polling;
    private volatile int generation;
    private boolean busy, draining, melodyLoading;
    private int pendingBytes;
    private long offset, lastBeat = -1, melodyVersion = -1;
    private String lastTurn = "", spokenTurn = "";
    private LinearLayout root;
    private EditText inviteField, nameField;
    private CheckBox mc, consent;
    private TextView status, audioStatus, arena, roster, verses;
    private Button joinButton, activateButton;
    private NativeAudio audio;
    private ToneGenerator click;
    private MediaPlayer melody;
    private TextToSpeech speech;
    private boolean speechReady;
    private final Runnable pollLoop = this::poll;
    private final Runnable beatLoop = new Runnable() {
        @Override public void run() { tickBeat(); if (foreground) ui.postDelayed(this, 20); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(10, 12, 18));
        getWindow().setNavigationBarColor(Color.rgb(10, 12, 18));
        audio = new NativeAudio(this);
        click = new ToneGenerator(AudioManager.STREAM_MUSIC, 15);
        speech = new TextToSpeech(this, result -> {
            speechReady = result == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(new Locale("pt", "BR"));
        });
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) {
                if (audioReady && utteranceId.equals(spokenTurn)) runAction("synthetic-done", obj("turnId", utteranceId));
            }
            @Override public void onError(String utteranceId) { ui.post(() -> message("Voz sintética indisponível. Instale uma voz em português nas configurações do Android.")); }
        });
        showEntry();
        acceptIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent); }
    private void acceptIntent(Intent intent) {
        String value = intent.getStringExtra("invite");
        if (value != null && token.isEmpty()) try { new RoomLink(value); inviteField.setText(value); }
        catch (IllegalArgumentException e) { message(e.getMessage()); }
    }
    private void page() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(36)); root.setBackgroundColor(Color.rgb(10, 12, 18));
        scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(WindowInsets.Type.systemBars()) : null;
            if (bars != null) root.setPadding(dp(22), dp(20) + bars.top, dp(22), dp(24) + bars.bottom);
            return insets;
        });
        label("FREE FLOWS", 29, Color.rgb(255, 121, 72));
        label("ANDROID · TESTE", 12, Color.LTGRAY);
    }
    private void showEntry() {
        page(); label("O próximo verso é seu.", 34, Color.WHITE);
        label("Entre na mesma sala do iPhone.", 16, Color.LTGRAY);
        inviteField = field("Convite HTTPS da sala", 101);
        inviteField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        inviteField.setText(getPreferences(0).getString("invite", ""));
        nameField = field("Seu nome de MC", 102); nameField.setText(getPreferences(0).getString("name", ""));
        mc = check("Quero competir como MC", true, 103);
        consent = check("Autorizo enviar meus turnos ao Gemini para transcrição e análise", false, 104);
        joinButton = button("Entrar na arena", 105, this::join);
        status = label("Cole o convite atual para começar.", 15, Color.LTGRAY);
        label("Use fones. O microfone só será solicitado ao ativar o áudio. Mantenha o app aberto durante o teste.", 13, Color.LTGRAY);
    }
    private void showRoom() {
        page(); arena = label("Carregando a sala…", 23, Color.WHITE);
        status = label("Você entrou.", 15, Color.LTGRAY);
        audioStatus = label("Áudio desligado", 14, Color.LTGRAY);
        activateButton = button("Ativar / retomar áudio", 106, this::activateAudio);
        button("Compartilhar convite", 107, () -> {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.invitation());
            startActivity(Intent.createChooser(share, "Convite da sala"));
        });
        consent = check("Autorizo meus turnos no Gemini", consent.isChecked(), 104);
        if (!link.control.isEmpty()) {
            button("Batalhar com disponíveis", 108, () -> {
                if (!consent.isChecked()) { message("Autorize seus turnos de áudio primeiro."); return; }
                runTask(() -> {
                    JSONObject current = room;
                    if (current == null || !"live".equals(current.optString("mode")) || "complete".equals(current.optString("phase")))
                        request("real-lobby", obj("consent", true));
                    request("practice", obj());
                });
            });
            button("Treinar sozinho com voz roteirizada", 109, () -> {
                if (!consent.isChecked()) { message("Autorize seus turnos de áudio primeiro."); return; }
                if (!audioReady) { message("Ative o áudio antes de iniciar o treino."); return; }
                runTask(() -> { request("real-lobby", obj("consent", true)); request("solo", obj("consent", true, "bars", 2, "bpm", 90)); });
            });
            label("No treino solo, o oponente usa versos roteirizados e voz sintética; não improvisa.", 13, Color.LTGRAY);
        }
        roster = label("", 16, Color.WHITE);
        verses = label("Aguardando versos…", 16, Color.LTGRAY);
        button("Sair da sala", 110, this::leave);
        label("Versão de teste · áudio entre Android e iPhone ainda em validação.", 12, Color.LTGRAY);
    }
    private void join() {
        if (busy) return;
        try {
            RoomLink parsed = new RoomLink(inviteField.getText().toString());
            String name = nameField.getText().toString().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Digite seu nome de MC.");
            if (mc.isChecked() && !consent.isChecked()) throw new IllegalArgumentException("Autorize o envio dos turnos para competir.");
            if (link != null && (!link.invitation().equals(parsed.invitation()))) { token = ""; id = ""; }
            link = parsed;
            boolean compete = mc.isChecked();
            getPreferences(0).edit().putString("invite", parsed.invitation()).putString("name", name).apply();
            busy = true; joinButton.setEnabled(false); message("Entrando…");
            int owner = generation;
            network.execute(() -> {
                try {
                    JSONObject health = request("health", null);
                    if (health.optJSONObject("nativeAudio") == null || health.getJSONObject("nativeAudio").optInt("version") != 1)
                        throw new IOException("Servidor sem suporte a áudio nativo.");
                    if (!token.isEmpty()) request("poll", obj());
                    if (token.isEmpty()) {
                        JSONObject session = request("join", obj("name", name, "role", compete ? "mc" : "spectator", "invite", parsed.invite, "audioTransport", "native-pcm"));
                        if (owner != generation) return;
                        token = session.getString("token"); id = session.getString("id");
                    }
                    ui.post(() -> { if (owner != generation) return; busy = false; showRoom(); polling = true; poll(); });
                } catch (Exception e) { ui.post(() -> { if (owner != generation) return; busy = false; joinButton.setEnabled(true); message(safeError(e)); }); }
            });
        } catch (Exception e) { message(e.getMessage()); }
    }
    private JSONObject request(String path, JSONObject body) throws Exception {
        RoomLink connection = link;
        if (connection == null) throw new IOException("Entre na sala primeiro.");
        Request.Builder builder = new Request.Builder().url(connection.base + "/" + path);
        if (body != null) builder.post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")));
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        if (!connection.control.isEmpty()) builder.header("X-Host-Token", connection.control);
        try (Response response = http.newCall(builder.build()).execute()) {
            JSONObject result;
            String text = response.body() == null ? "{}" : response.body().string();
            try { result = new JSONObject(text); }
            catch (JSONException e) { throw new IOException("Servidor indisponível. Confira o convite atual da sala."); }
            if (!response.isSuccessful()) {
                if (path.equals("poll") && (response.code() == 401 || response.code() == 403 || response.code() == 404)) { token = ""; id = ""; }
                throw new IOException(result.optString("error", "Erro HTTP " + response.code()));
            }
            return result;
        }
    }
    private void poll() {
        ui.removeCallbacks(pollLoop);
        if (!foreground || !polling || token.isEmpty()) return;
        int owner = generation;
        network.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                JSONObject state = request("poll", obj()).getJSONObject("state");
                long now = System.currentTimeMillis();
                ui.post(() -> {
                    if (owner != generation || !polling || !foreground) return;
                    offset = state.optLong("serverNow", now) - (start + now) / 2;
                    room = state; render(); ui.postDelayed(pollLoop, 500);
                });
            } catch (Exception e) { ui.post(() -> {
                if (owner != generation) return;
                message(safeError(e));
                if (token.isEmpty()) { stopAudio(); polling = false; showEntry(); message("Sessão expirada. Entre novamente com o convite atual."); }
                else if (polling && foreground) ui.postDelayed(pollLoop, 2000);
            }); }
        });
    }
    private JSONObject member(String memberId) {
        JSONObject state = room; if (state == null) return null;
        JSONArray members = state.optJSONArray("members");
        if (members != null) for (int i = 0; i < members.length(); i++) {
            JSONObject p = members.optJSONObject(i); if (p != null && memberId.equals(p.optString("id"))) return p;
        }
        return null;
    }
    private JSONObject battle() {
        JSONObject state = room; if (state == null) return null;
        JSONArray matches = state.optJSONArray("matches"); if (matches == null) return null;
        JSONObject next = null;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.optJSONObject(i); if (m == null) continue;
            if (m.optString("id").equals(state.optString("active"))) return m;
            if (next == null && "pending".equals(m.optString("status"))) next = m;
        }
        return next != null ? next : matches.optJSONObject(matches.length() - 1);
    }
    private boolean active(String memberId) {
        JSONObject state = room, b = battle();
        return state != null && "live".equals(state.optString("phase")) && b != null
            && (memberId.equals(b.optString("a")) || memberId.equals(b.optString("b")));
    }
    private String memberName(String memberId) { JSONObject p = member(memberId); return p == null ? "A definir" : p.optString("name"); }
    private void render() {
        JSONObject state = room; if (state == null) return;
        JSONObject turn = state.optJSONObject("turn"), b = battle();
        String title = b == null ? "A arena te espera" : memberName(b.optString("a")) + " × " + memberName(b.optString("b"));
        String phase = state.optString("phase");
        arena.setText(title + "\n" + ("live".equals(phase) && turn != null ? (id.equals(turn.optString("speaker")) ? "SUA VEZ" : memberName(turn.optString("speaker"))) : phase));
        JSONObject management = state.optJSONObject("management");
        if (management != null && !management.optString("message").isEmpty()) message(management.optString("message"));
        StringBuilder people = new StringBuilder("NA SALA\n");
        JSONArray members = state.optJSONArray("members");
        if (members != null) for (int i = 0; i < members.length(); i++) {
            JSONObject p = members.optJSONObject(i); if (p == null) continue;
            people.append(p.optString("name")).append(" · ").append(p.optString("role"));
            people.append(p.optBoolean("audioReady") ? " · áudio pronto" : " · áudio desligado").append('\n');
        }
        roster.setText(people);
        JSONArray transcripts = state.optJSONArray("transcripts"); StringBuilder text = new StringBuilder("VERSO A VERSO\n");
        if (transcripts != null) for (int i = Math.max(0, transcripts.length() - 8); i < transcripts.length(); i++) {
            JSONObject verse = transcripts.optJSONObject(i); if (verse == null) continue;
            text.append('\n').append(memberName(verse.optString("speaker"))).append(": ").append(verse.optString("text")).append('\n');
        }
        if (b != null && !b.isNull("assessment")) text.append("\nLEITURA DA BATALHA\n").append(b.optString("assessment"));
        verses.setText(text);
        String turnId = turn == null ? "" : turn.optString("id");
        if (!lastTurn.equals(turnId)) { queue(segments.flush(false)); lastTurn = turnId; }
        if (!active(id)) queue(segments.flush(false));
        if (audioReady && "training".equals(state.optString("mode")) && turn != null && !turnId.equals(spokenTurn)) {
            JSONObject speaker = member(turn.optString("speaker"));
            if (speaker != null && speaker.optBoolean("simulated") && transcripts != null) {
                for (int i = 0; i < transcripts.length(); i++) {
                    JSONObject verse = transcripts.optJSONObject(i);
                    if (verse != null && turnId.equals(verse.optString("turn"))) {
                        if (!speechReady) { message("Voz sintética não está disponível neste aparelho."); break; }
                        spokenTurn = turnId; speech.speak(verse.optString("text"), TextToSpeech.QUEUE_FLUSH, null, turnId); break;
                    }
                }
            }
        }
        JSONObject beat = state.optJSONObject("beat");
        if (audioReady && beat != null && beat.optLong("version") != melodyVersion && !melodyLoading) loadMelody(beat.optLong("version"));
    }
    private void activateAudio() {
        JSONObject me = member(id); if (me == null) return;
        if ("mc".equals(me.optString("role")) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11); return;
        }
        stopAudio(); int owner = generation;
        try {
            audio.start("mc".equals(me.optString("role")), this::microphone, message -> ui.post(() -> audioFailed(message)));
            Request request = new Request.Builder().url(link.base.replaceFirst("^https:", "wss:") + "/native-audio")
                .header("Authorization", "Bearer " + token).build();
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    network.execute(() -> {
                        try {
                            if (owner != generation || socket != ws || !foreground) { ws.cancel(); return; }
                            request("ready", obj("ready", true));
                            ui.post(() -> { if (owner != generation || socket != ws || !foreground) return; audioReady = true;
                                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                                audioStatus.setText("Áudio ativo · microfone transmite só no confronto"); });
                        } catch (Exception e) { ui.post(() -> { if (socket == ws) audioFailed(safeError(e)); }); }
                    });
                }
                @Override public void onMessage(WebSocket ws, ByteString bytes) {
                    if (socket != ws || !audioReady || bytes.size() <= 36) return;
                    byte[] data = bytes.toByteArray(); String sender = new String(data, 0, 36, StandardCharsets.US_ASCII);
                    if (active(sender)) audio.receive(sender, Arrays.copyOfRange(data, 36, data.length));
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    try { String error = new JSONObject(text).optString("error"); if (!error.isEmpty()) ui.post(() -> { if (socket == ws) audioFailed(error); }); }
                    catch (JSONException ignored) { }
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { ui.post(() -> { if (socket == ws) audioFailed("Conexão de áudio perdida. Toque em Retomar áudio."); }); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { ui.post(() -> { if (socket == ws) audioFailed("Áudio desconectado. Toque em Retomar áudio."); }); }
            });
            audioStatus.setText("Conectando áudio…");
        } catch (Exception e) { audioFailed(safeError(e)); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 11) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) activateAudio();
            else audioStatus.setText("Microfone negado. Permita em Configurações → Aplicativos → Free Flows → Permissões.");
        }
    }
    private void microphone(byte[] pcm) {
        JSONObject state = room; WebSocket relay = socket;
        if (!audioReady || !active(id) || relay == null || state == null) return;
        JSONObject turn = state.optJSONObject("turn"); if (turn == null) return;
        if (relay.queueSize() < 32000) relay.send(ByteString.of(pcm));
        queue(segments.consume(pcm, turn.optString("id")));
    }
    private void queue(SpeechSegments.Segment segment) {
        if (segment == null) return;
        synchronized (pending) {
            if (pendingBytes + segment.wav().length > 15 * 1024 * 1024) { ui.post(() -> message("Fila de transcrição cheia. Este trecho não foi guardado.")); return; }
            pending.add(segment); pendingBytes += segment.wav().length;
            if (draining) return; draining = true;
        }
        int owner = generation;
        upload.execute(() -> {
            try {
                while (owner == generation) {
                    SpeechSegments.Segment next;
                    synchronized (pending) { next = pending.peek(); }
                    if (next == null) break;
                    boolean delivered = false;
                    for (int attempt = 0; attempt < 3 && owner == generation; attempt++) {
                        try {
                            request("audio", obj("turnId", next.turnId(), "chunkIndex", next.index(), "speechEnded", next.ended(),
                                "mimeType", "audio/wav", "data", android.util.Base64.encodeToString(next.wav(), android.util.Base64.NO_WRAP)));
                            delivered = true; break;
                        } catch (Exception e) {
                            ui.post(() -> message("Transcrição pendente nesta sessão. A voz ao vivo continua."));
                            if (attempt < 2) try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                    if (!delivered) break;
                    synchronized (pending) { if (pending.peek() == next) { pending.remove(); pendingBytes -= next.wav().length; } }
                }
            } finally { synchronized (pending) { draining = false; } }
        });
    }
    private void loadMelody(long version) {
        melodyLoading = true; int owner = generation; RoomLink connection = link;
        network.execute(() -> {
            try (Response response = http.newCall(new Request.Builder().url(connection.base + "/beat?invite=" + android.net.Uri.encode(connection.invite)).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Beat indisponível");
                File file = new File(getCacheDir(), "beat-audio");
                try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192]; int n, total = 0;
                    while ((n = in.read(buffer)) != -1) { total += n; if (total > 8 * 1024 * 1024) throw new IOException("Beat muito grande"); out.write(buffer, 0, n); }
                }
                ui.post(() -> {
                    melodyLoading = false; if (owner != generation || !audioReady) return;
                    try {
                        if (melody != null) melody.release(); melody = new MediaPlayer();
                        melody.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                        melody.setDataSource(file.getAbsolutePath()); melody.setLooping(true); melody.setVolume(.25f, .25f);
                        melody.setOnPreparedListener(player -> { if (player == melody) { melodyVersion = version; lastBeat = -1; } });
                        melody.prepareAsync();
                    } catch (Exception e) { if (melody != null) melody.release(); melody = null; message("Beat indisponível; usando clique local."); }
                });
            } catch (Exception e) { ui.post(() -> { melodyLoading = false; melodyVersion = version; message("Beat indisponível; usando clique local."); }); }
        });
    }
    private void tickBeat() {
        JSONObject state = room;
        if (!audioReady || state == null || !"live".equals(state.optString("phase")) || state.isNull("beatEpoch")) {
            if (melody != null && melodyVersion >= 0) try { if (melody.isPlaying()) melody.pause(); } catch (IllegalStateException ignored) { }
            lastBeat = -1; return;
        }
        long now = System.currentTimeMillis() + offset, epoch = state.optLong("beatEpoch"); if (now < epoch) return;
        if (melody != null && melodyVersion >= 0) try {
            if (!melody.isPlaying()) { melody.seekTo((int)((now - epoch) % Math.max(1, melody.getDuration()))); melody.start(); }
            return;
        } catch (IllegalStateException ignored) { }
        JSONObject config = state.optJSONObject("config"); double bpm = config == null ? 90 : config.optDouble("bpm", 90);
        long beat = (long)((now - epoch) / (60000 / Math.max(1, bpm)));
        if (beat != lastBeat) { lastBeat = beat; click.startTone(ToneGenerator.TONE_PROP_BEEP, 40); }
    }
    private void audioFailed(String error) {
        stopAudio(); if (audioStatus != null) audioStatus.setText(error);
        if (!token.isEmpty()) runAction("ready", obj("ready", false));
    }
    private void stopAudio() {
        audioReady = false; WebSocket old = socket; socket = null; if (old != null) old.cancel();
        audio.stop(); speech.stop(); spokenTurn = ""; lastBeat = -1;
        if (melody != null) { melody.release(); melody = null; } melodyVersion = -1;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void leave() {
        if (!token.isEmpty()) runAction("ready", obj("ready", false));
        generation++; polling = false; ui.removeCallbacks(pollLoop); stopAudio();
        token = ""; id = ""; room = null; link = null; lastTurn = ""; segments.flush(false);
        synchronized (pending) { pending.clear(); pendingBytes = 0; }
        showEntry();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; ui.removeCallbacks(beatLoop); ui.post(beatLoop);
        if (!token.isEmpty()) { polling = true; poll(); }
    }
    @Override protected void onPause() {
        foreground = false; polling = false; ui.removeCallbacks(pollLoop); ui.removeCallbacks(beatLoop);
        queue(segments.flush(false)); stopAudio();
        if (!token.isEmpty()) runAction("ready", obj("ready", false));
        if (audioStatus != null) audioStatus.setText("Áudio pausado. Ao voltar, toque em Retomar áudio.");
        super.onPause();
    }
    @Override protected void onDestroy() {
        generation++; ui.removeCallbacksAndMessages(null); stopAudio(); click.release(); speech.shutdown();
        network.shutdownNow(); upload.shutdownNow(); http.dispatcher().cancelAll(); super.onDestroy();
    }
    private interface Task { void run() throws Exception; }
    private void runTask(Task task) {
        int owner = generation;
        network.execute(() -> { try { task.run(); } catch (Exception e) { ui.post(() -> { if (owner == generation) message(safeError(e)); }); } });
    }
    private void runAction(String path, JSONObject body) { runTask(() -> request(path, body)); }
    private static String safeError(Exception e) {
        return e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("https://") ? e.getMessage() : "Não foi possível conectar. Confira a rede e o convite atual.";
    }
    static JSONObject obj(Object... fields) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < fields.length; i += 2) result.put((String)fields[i], fields[i + 1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); } return result;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(12)); root.addView(view); return view;
    }
    private EditText field(String hint, int id) {
        EditText view = new EditText(this); view.setId(id); view.setHint(hint); view.setTextColor(Color.WHITE);
        view.setHintTextColor(Color.LTGRAY); view.setSingleLine(true); view.setTextSize(16);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); root.addView(view); return view;
    }
    private CheckBox check(String text, boolean selected, int id) {
        CheckBox view = new CheckBox(this); view.setId(id); view.setText(text); view.setTextColor(Color.WHITE);
        view.setChecked(selected); view.setPadding(0, dp(8), 0, dp(8)); root.addView(view); return view;
    }
    private Button button(String text, int id, Runnable action) {
        Button view = new Button(this); view.setId(id); view.setText(text); view.setAllCaps(false);
        view.setTextColor(Color.WHITE); view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(183, 65, 36)));
        view.setOnClickListener(v -> action.run()); root.addView(view); return view;
    }
    private void message(String text) { if (status != null) status.setText(text); }
}
