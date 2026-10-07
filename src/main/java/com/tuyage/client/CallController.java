package com.tuyage.client;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import com.tuyage.proto.CallPacket;
import com.tuyage.proto.CallPacketType;
import com.tuyage.proto.Group;
import com.tuyage.proto.User;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javafx.application.Platform;

final class CallController {
    // Format historique garde pour les sonneries et pour la compatibilite avec
    // d'anciens clients qui n'envoient pas encore les metadonnees audio.
    // Format UNIQUE sur le réseau. Les périphériques peuvent capturer dans un autre
    // format, mais chaque trame est convertie avant gRPC. Cela évite qu'un pilote
    // Windows ou une carte son différente transforme la voix en bruit numérique.
    private static final AudioFormat CALL_AUDIO_FORMAT = new AudioFormat(48000f, 16, 1, true, false);
    private static final int OPUS_FRAME_SAMPLES = 960; // 20 ms @ 48 kHz
    private static final int OPUS_MAX_PACKET_BYTES = 4000;
    private static final int OPUS_BITRATE = 32000; // voix claire, ~24x moins que PCM brut
    private static final String AUDIO_CODEC_OPUS = "opus";

    // Les pilotes Windows ne proposent pas tous le meme format de microphone.
    // On choisit le premier format reellement ouvrable au lieu d'imposer 44,1 kHz.
    private static final AudioFormat[] CALL_AUDIO_CANDIDATES = new AudioFormat[] {
            new AudioFormat(48000f, 16, 1, true, false),
            new AudioFormat(48000f, 16, 2, true, false),
            new AudioFormat(44100f, 16, 1, true, false),
            new AudioFormat(44100f, 16, 2, true, false),
            new AudioFormat(32000f, 16, 1, true, false),
            new AudioFormat(32000f, 16, 2, true, false),
            new AudioFormat(24000f, 16, 1, true, false),
            new AudioFormat(22050f, 16, 1, true, false),
            new AudioFormat(16000f, 16, 1, true, false),
            new AudioFormat(16000f, 16, 2, true, false),
            new AudioFormat(8000f, 16, 1, true, false)
    };

    private static final Preferences AUDIO_PREFS = Preferences.userNodeForPackage(CallController.class);

    private static final int AUDIO_FRAME_MS = 20;
    private static final int JITTER_TARGET_FRAMES = 5; // ~100 ms: compromis voix fluide / latence faible
    private static final int MAX_AUDIO_FRAMES = 10;    // plafond ~200 ms
    private static final long MAX_FRAME_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(320);
    private static final long REBUFFER_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos(140);
    private static final long AUDIO_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(AUDIO_FRAME_MS);

    private record AudioFrame(long sequence, long arrivalNanos, byte[] data,
                              int sampleRate, int sampleBits, int channels) implements Comparable<AudioFrame> {
        @Override public int compareTo(AudioFrame other) { return Long.compare(sequence, other.sequence); }

        AudioFormat format() {
            int rate = sampleRate > 0 ? sampleRate : 44100;
            int bits = sampleBits > 0 ? sampleBits : 16;
            int ch = channels > 0 ? channels : 1;
            return new AudioFormat((float) rate, bits, ch, true, false);
        }
    }
    private final ClientApp app;
    private final GrpcClient api;
    private final User me;
    private final LongFunction<User> userResolver;
    private final LongFunction<Group> groupResolver;
    private volatile CallSession current;
    private volatile String pendingIncomingCallId = "";
    private volatile IncomingCallWindow pendingIncomingWindow;
    private final AtomicBoolean ringtoneAudioActive = new AtomicBoolean(false);
    private Thread ringtoneAudioThread;
    private volatile Clip incomingRingtoneClip;

    CallController(ClientApp app, GrpcClient api, User me, LongFunction<User> userResolver, LongFunction<Group> groupResolver) {
        this.app = app;
        this.api = api;
        this.me = me;
        this.userResolver = userResolver;
        this.groupResolver = groupResolver;
        api.startCallChannel(this::handlePacket, error -> Platform.runLater(() -> {
            // IMPORTANT: le canal média gRPC se reconnecte automatiquement.
            // Une micro-coupure ne doit surtout pas fermer l'appel juste après
            // avoir décroché. On garde donc la session et on informe seulement
            // l'utilisateur pendant la reconnexion.
            CallSession s = current;
            if (s != null) {
                System.err.println("[Tuyage CALL] Canal média interrompu, reconnexion automatique: " + safeMessage(error));
                s.window.setWarning("Connexion audio/vidéo momentanément interrompue — reconnexion…");
            }
        }));
    }

    void startOutgoing(User target, boolean video) {
        if (target == null) return;
        if (current != null) { app.info("Un appel est déjà en cours."); return; }
        if (!target.getOnline()) {
            app.info(target.getDisplayName() + " n'est pas en ligne actuellement.");
            return;
        }
        String callId = UUID.randomUUID().toString();
        CallSession session = new CallSession(callId, target.getId(), 0, target.getDisplayName(), video, true);
        current = session;
        session.window.showRinging(true, video);
        // Celui qui appelle entend lui aussi une tonalite d'attente jusqu'a
        // l'acceptation, au refus ou au raccrochage.
        startOutgoingRingback();
        api.sendCall(target.getId(), CallPacketType.CALL_OFFER, callId, new byte[0], video,
                video ? "Appel vidéo" : "Appel audio");
    }

    void startGroupOutgoing(Group group, boolean video) {
        if (group == null) return;
        if (current != null) { app.info("Un appel est déjà en cours."); return; }
        if (group.getMemberCount() < 2) { app.info("Ajoute au moins un autre membre avant de lancer l'appel."); return; }
        String callId = UUID.randomUUID().toString();
        CallSession session = new CallSession(callId, 0, group.getId(), group.getName(), video, true);
        current = session;
        session.window.showRinging(true, video);
        session.window.setStatus(video ? "Appel vidéo de groupe… les membres peuvent rejoindre" : "Appel audio de groupe… les membres peuvent rejoindre");
        startOutgoingRingback();
        api.sendGroupCall(group.getId(), 0, CallPacketType.CALL_OFFER, callId, video, video ? "Appel vidéo de groupe" : "Appel audio de groupe");
    }

    void handleExternalSignal(CallPacket packet) {
        if (packet == null) return;
        // Les paquets media ne doivent jamais passer par le thread JavaFX :
        // ils arrivent plusieurs dizaines de fois par seconde.
        if (packet.getType() == CallPacketType.CALL_AUDIO || packet.getType() == CallPacketType.CALL_VIDEO) {
            handlePacket(packet);
            return;
        }
        Platform.runLater(() -> handleSignal(packet));
    }

    private void handlePacket(CallPacket packet) {
        if (packet.getType() == CallPacketType.CALL_AUDIO) {
            CallSession s = current;
            if (s != null && s.matches(packet)) s.receiveAudio(packet);
            return;
        }
        if (packet.getType() == CallPacketType.CALL_VIDEO) {
            CallSession s = current;
            if (s != null && s.matches(packet)) s.receiveVideo(packet);
            return;
        }
        Platform.runLater(() -> handleSignal(packet));
    }

    private void handleSignal(CallPacket packet) {
        switch (packet.getType()) {
            case CALL_OFFER -> incomingOffer(packet);
            case CALL_ACCEPT -> {
                CallSession s = current;
                if (s != null && s.matches(packet)) {
                    if (s.groupId > 0 && s.active.get()) {
                        User joined = userResolver.apply(packet.getFromUserId());
                        String joinedName = joined == null ? "Un membre" : joined.getDisplayName();
                        s.window.setStatus(joinedName + " a rejoint l'appel");
                    }
                    s.accepted();
                }
            }
            case CALL_REJECT -> {
                CallSession s = current;
                if (s != null && s.matches(packet)) {
                    if (s.groupId > 0) s.window.setStatus(packet.getText().isBlank() ? "Un membre a ignoré l'appel" : packet.getText());
                    else s.finishWithBusyTone(packet.getText().isBlank() ? "Appel refusé" : packet.getText());
                }
            }
            case CALL_UNAVAILABLE -> {
                CallSession s = current;
                if (s != null && s.matches(packet)) s.finishWithBusyTone(packet.getText().isBlank() ? "Utilisateur indisponible" : packet.getText());
            }
            case CALL_END -> {
                CallSession s = current;
                if (s != null && s.matches(packet)) s.finish(packet.getGroupId()>0 ? "Appel de groupe annulé" : "Appel terminé", false);
                else if (packet.getCallId().equals(pendingIncomingCallId)) {
                    IncomingCallWindow incoming = pendingIncomingWindow;
                    pendingIncomingCallId = "";
                    pendingIncomingWindow = null;
                    stopRingtone();
                    if (incoming != null) incoming.closeSilently();
                }
            }
            case CALL_LEAVE -> {
                CallSession s = current;
                if (s != null && s.groupId > 0 && s.matches(packet)) {
                    User left = userResolver.apply(packet.getFromUserId());
                    String leftName = left == null ? "Un membre" : left.getDisplayName();
                    s.window.setStatus(leftName + " a quitté l'appel");
                }
            }
            default -> { }
        }
    }

    private void incomingOffer(CallPacket packet) {
        if (packet.getCallId().equals(pendingIncomingCallId)) return;
        CallSession existing = current;
        if (existing != null && existing.matches(packet)) return;
        User caller = userResolver.apply(packet.getFromUserId());
        String callerName = caller == null ? "Utilisateur " + packet.getFromUserId() : caller.getDisplayName();
        Group group = packet.getGroupId() > 0 ? groupResolver.apply(packet.getGroupId()) : null;
        String groupName = group == null ? "" : group.getName();
        if (current != null) {
            if (packet.getGroupId() > 0) api.sendGroupCall(packet.getGroupId(), packet.getFromUserId(), CallPacketType.CALL_REJECT, packet.getCallId(), packet.getVideoEnabled(), "Occupé");
            else api.sendCall(packet.getFromUserId(), CallPacketType.CALL_REJECT, packet.getCallId(), new byte[0], packet.getVideoEnabled(), "Occupé");
            return;
        }

        app.stage().setIconified(false);
        app.stage().toFront();
        pendingIncomingCallId = packet.getCallId();
        startIncomingRingtone();

        Runnable reject = () -> {
            if (!packet.getCallId().equals(pendingIncomingCallId)) return;
            stopRingtone();
            pendingIncomingCallId = "";
            pendingIncomingWindow = null;
            if (packet.getGroupId() > 0) api.sendGroupCall(packet.getGroupId(), packet.getFromUserId(), CallPacketType.CALL_REJECT, packet.getCallId(), packet.getVideoEnabled(), "Invitation ignorée");
            else api.sendCall(packet.getFromUserId(), CallPacketType.CALL_REJECT, packet.getCallId(), new byte[0], packet.getVideoEnabled(), "Appel refusé");
        };

        Runnable accept = () -> {
            if (!packet.getCallId().equals(pendingIncomingCallId)) return;
            stopRingtone();
            pendingIncomingCallId = "";
            pendingIncomingWindow = null;
            String peerName = packet.getGroupId() > 0 ? groupName : callerName;
            CallSession session = new CallSession(packet.getCallId(), packet.getFromUserId(), packet.getGroupId(), peerName, packet.getVideoEnabled(), false);
            current = session;
            session.window.showRinging(false, packet.getVideoEnabled());
            if (packet.getGroupId() > 0) api.sendGroupCall(packet.getGroupId(), packet.getFromUserId(), CallPacketType.CALL_ACCEPT, packet.getCallId(), packet.getVideoEnabled(), "A rejoint l'appel");
            else api.sendCall(packet.getFromUserId(), CallPacketType.CALL_ACCEPT, packet.getCallId(), new byte[0], packet.getVideoEnabled(), "Accepté");
            session.accepted();
        };

        IncomingCallWindow incoming = new IncomingCallWindow(app, callerName, groupName, packet.getVideoEnabled(), packet.getGroupId() > 0, accept, reject);
        pendingIncomingWindow = incoming;
        incoming.show();
    }

    /**
     * Tonalité entendue par la personne qui APPELLE :
     * un seul "Dooo" lent, puis une vraie pause, comme un ringback téléphonique.
     */
    private void startOutgoingRingback() {
        startToneLoop(true);
    }

    /**
     * Sonnerie entendue par la personne qui RECOIT l'appel.
     *
     * Tuyage charge d'abord un vrai fichier WAV depuis les ressources afin
     * d'obtenir une sonnerie de smartphone plus naturelle. Si le fichier est
     * absent ou non lisible, l'application revient automatiquement a la
     * sonnerie synthetique precedente.
     *
     * Pour utiliser une sonnerie personnelle legalement obtenue, il suffit de
     * remplacer /sounds/incoming-ringtone.wav puis de refaire Clean and Build.
     */
    private void startIncomingRingtone() {
        stopRingtone();
        ringtoneAudioActive.set(true);
        ringtoneAudioThread = new Thread(() -> {
            Clip clip = null;
            try (InputStream raw = CallController.class.getResourceAsStream("/sounds/incoming-ringtone.wav")) {
                if (raw == null) {
                    runSyntheticToneLoop(false);
                    return;
                }
                try (BufferedInputStream buffered = new BufferedInputStream(raw);
                     AudioInputStream audio = AudioSystem.getAudioInputStream(buffered)) {
                    clip = AudioSystem.getClip();
                    incomingRingtoneClip = clip;
                    clip.open(audio);
                    clip.setFramePosition(0);
                    clip.loop(Clip.LOOP_CONTINUOUSLY);
                    clip.start();
                    while (ringtoneAudioActive.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Secours fiable si Java Sound ne peut pas lire le WAV.
                runSyntheticToneLoop(false);
            } finally {
                if (clip != null) {
                    try { clip.stop(); } catch (Exception ignored) {}
                    try { clip.flush(); } catch (Exception ignored) {}
                    try { clip.close(); } catch (Exception ignored) {}
                }
                if (incomingRingtoneClip == clip) incomingRingtoneClip = null;
            }
        }, "tuyage-incoming-ringtone");
        ringtoneAudioThread.setDaemon(true);
        ringtoneAudioThread.start();
    }

    private void startToneLoop(boolean outgoing) {
        stopRingtone();
        ringtoneAudioActive.set(true);
        ringtoneAudioThread = new Thread(() -> runSyntheticToneLoop(outgoing),
                outgoing ? "tuyage-outgoing-ringback" : "tuyage-incoming-ringtone");
        ringtoneAudioThread.setDaemon(true);
        ringtoneAudioThread.start();
    }

    private void runSyntheticToneLoop(boolean outgoing) {
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(CALL_AUDIO_FORMAT);
            line.open(CALL_AUDIO_FORMAT, 4096);
            line.start();
            while (ringtoneAudioActive.get() && !Thread.currentThread().isInterrupted()) {
                if (outgoing) {
                    // Appelant : Dooo........ Dooo........ Dooo........
                    playTone(line, 425.0, 1050);
                    if (!tonePause(3400)) break;
                } else {
                    // Repli si le fichier WAV de sonnerie est indisponible.
                    playTone(line, 784.0, 210);
                    if (!tonePause(90)) break;
                    playTone(line, 988.0, 230);
                    if (!tonePause(90)) break;
                    playTone(line, 784.0, 210);
                    if (!tonePause(760)) break;
                }
            }
        } catch (Throwable ignored) {
            // L'interface Accepter/Refuser reste utilisable meme sans sortie audio.
        } finally {
            if (line != null) {
                try { line.flush(); } catch (Exception ignored) {}
                try { line.stop(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void playTone(SourceDataLine line, double frequency, int durationMs) {
        if (!ringtoneAudioActive.get() || Thread.currentThread().isInterrupted()) return;
        byte[] tone = createTone(frequency, durationMs);
        line.write(tone, 0, tone.length);
    }

    /** Pause interruptible : permet de couper la tonalite instantanement. */
    private boolean tonePause(long millis) {
        long remaining = millis;
        while (ringtoneAudioActive.get() && remaining > 0 && !Thread.currentThread().isInterrupted()) {
            long step = Math.min(remaining, 50L);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
        }
        return ringtoneAudioActive.get() && !Thread.currentThread().isInterrupted();
    }

    /** Tonalité courte "tu-tu-tu-tu" pour refus, occupé ou indisponible. */
    private void startBusyTone(Runnable afterTone) {
        stopRingtone();
        ringtoneAudioActive.set(true);
        ringtoneAudioThread = new Thread(() -> {
            SourceDataLine line = null;
            try {
                line = AudioSystem.getSourceDataLine(CALL_AUDIO_FORMAT);
                line.open(CALL_AUDIO_FORMAT, 2048);
                line.start();
                for (int i = 0; i < 4 && ringtoneAudioActive.get(); i++) {
                    playTone(line, 485.0, 190);
                    if (i < 3 && !tonePause(125)) break;
                }
                tonePause(250);
            } catch (Throwable ignored) {
            } finally {
                if (line != null) {
                    try { line.flush(); } catch (Exception ignored) {}
                    try { line.stop(); } catch (Exception ignored) {}
                    try { line.close(); } catch (Exception ignored) {}
                }
                ringtoneAudioActive.set(false);
                if (afterTone != null) Platform.runLater(afterTone);
            }
        }, "tuyage-busy-tone");
        ringtoneAudioThread.setDaemon(true);
        ringtoneAudioThread.start();
    }

    private void stopRingtone() {
        ringtoneAudioActive.set(false);
        Clip clip = incomingRingtoneClip;
        incomingRingtoneClip = null;
        if (clip != null) {
            try { clip.stop(); } catch (Exception ignored) {}
            try { clip.flush(); } catch (Exception ignored) {}
            try { clip.close(); } catch (Exception ignored) {}
        }
        Thread t = ringtoneAudioThread;
        ringtoneAudioThread = null;
        if (t != null) t.interrupt();
    }

    private final class CallSession {
        private final String callId;
        private final long peerId;
        private final long groupId;
        private final boolean video;
        private final boolean outgoing;
        private final CallWindow window;
        private final AtomicBoolean active = new AtomicBoolean(false);
        private final AtomicBoolean muted = new AtomicBoolean(false);
        private final AtomicBoolean cameraEnabled = new AtomicBoolean(true);
        private final PriorityBlockingQueue<AudioFrame> incomingAudio = new PriorityBlockingQueue<>();
        private final AtomicLong audioSequence = new AtomicLong();
        private final AtomicLong videoSequence = new AtomicLong();
        private final AtomicLong sentAudioPackets = new AtomicLong();
        private final AtomicLong receivedAudioPackets = new AtomicLong();
        private final AtomicLong sentVideoPackets = new AtomicLong();
        private final AtomicLong receivedVideoPackets = new AtomicLong();
        private volatile long lastPlayedAudioSequence = -1;
        private volatile long lastVideoSequence = -1;
        private volatile long lastRemotePlaybackNanos = 0L;
        private volatile double lastRemoteLevel = 0.0;
        private TargetDataLine microphone;
        private AudioFormat microphoneFormat;
        private SourceDataLine speaker;
        private AudioFormat speakerFormat;
        private volatile int speakerFrameBytes;
        private volatile String inputDeviceName = AUDIO_PREFS.get("callInputDevice", AudioDeviceUtil.AUTO);
        private volatile String outputDeviceName = AUDIO_PREFS.get("callOutputDevice", AudioDeviceUtil.AUTO);
        private volatile String activeInputDevice = "";
        private volatile String activeOutputDevice = "";
        private OpusEncoder opusEncoder;
        private OpusDecoder opusDecoder;
        private final AtomicLong playedAudioFrames = new AtomicLong();
        private Webcam webcam;
        private Thread micThread;
        private Thread speakerThread;
        private Thread videoThread;

        CallSession(String callId, long peerId, long groupId, String peerName, boolean video, boolean outgoing) {
            this.callId = callId;
            this.peerId = peerId;
            this.groupId = groupId;
            this.video = video;
            this.outgoing = outgoing;
            this.window = new CallWindow(app, peerName, video,
                    () -> finish("Appel terminé", true),
                    this::toggleMute,
                    this::toggleCamera,
                    this::chooseAudioDevices);
        }

        boolean matches(CallPacket packet) {
            if (!callId.equals(packet.getCallId())) return false;
            if (groupId > 0) return packet.getGroupId() == groupId;
            return packet.getFromUserId() == peerId || packet.getToUserId() == peerId || packet.getFromUserId() == 0;
        }

        void accepted() {
            stopRingtone();
            if (!active.compareAndSet(false, true)) {
                window.ensureVisible();
                return;
            }
            System.out.println("[Tuyage CALL] Appel accepté: " + callId + " — maintien de la fenêtre de conversation");
            window.connected();
            window.ensureVisible();
            startAudio();
            if (video) startVideo();
        }

        private void startAudio() {
            incomingAudio.clear();
            lastPlayedAudioSequence = -1L;
            try {
                opusEncoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                opusEncoder.setBitrate(OPUS_BITRATE);
                opusEncoder.setComplexity(7);
                opusEncoder.setUseVBR(true);
                opusEncoder.setUseInbandFEC(true);
                opusEncoder.setPacketLossPercent(10);
                opusDecoder = new OpusDecoder(48000, 1);
                System.out.println("[Tuyage CALL] Codec Opus actif: 48 kHz mono, 20 ms, " + OPUS_BITRATE/1000 + " kbit/s, FEC");
            } catch (Exception e) {
                opusEncoder = null; opusDecoder = null;
                System.err.println("[Tuyage CALL] Opus indisponible, repli PCM: " + safeMessage(e));
            }

            // La lecture distante est lancée indépendamment du microphone local.
            // Même si Windows refuse l'entrée micro, on doit toujours pouvoir
            // entendre le correspondant.
            speakerThread = new Thread(() -> {
                boolean primed = false;
                long lastRealFrameNanos = 0L;
                long underflows = 0L;
                long sequenceGaps = 0L;

                while (active.get()) {
                    try {
                        // Amorçage initial (ou après une vraie coupure réseau): environ 100 ms.
                        // Contrairement à la v3.0.11, on ne ré-amorce PAS chaque fois que
                        // la file devient momentanément vide entre deux paquets de 20 ms.
                        if (!primed) {
                            while (active.get() && incomingAudio.size() < JITTER_TARGET_FRAMES) {
                                Thread.sleep(2);
                            }
                            if (!active.get()) break;
                            primed = true;
                            lastRealFrameNanos = System.nanoTime();
                        }

                        // Empêcher la latence de dériver : si trop de voix s'est accumulée,
                        // on conserve la fenêtre la plus récente.
                        while (incomingAudio.size() > MAX_AUDIO_FRAMES) {
                            incomingAudio.poll();
                        }

                        AudioFrame frame = incomingAudio.poll(32, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            underflows++;
                            // Le buffer interne de SourceDataLine continue à jouer pendant
                            // les petits écarts de scheduling/réseau. On ne fabrique pas
                            // de silence et on ne redémarre pas immédiatement le jitter buffer.
                            if (lastRealFrameNanos > 0
                                    && System.nanoTime() - lastRealFrameNanos > REBUFFER_AFTER_NANOS) {
                                primed = false;
                                if (underflows == 1 || underflows % 10 == 0) {
                                    System.out.println("[Tuyage CALL] Rébufferisation audio courte • attente de vraies trames");
                                }
                            }
                            continue;
                        }

                        lastRealFrameNanos = System.nanoTime();

                        // Doublons / paquets véritablement en retard.
                        if (frame.sequence() > 0 && frame.sequence() <= lastPlayedAudioSequence) {
                            continue;
                        }

                        if (lastPlayedAudioSequence > 0 && frame.sequence() > lastPlayedAudioSequence + 1) {
                            sequenceGaps += (frame.sequence() - lastPlayedAudioSequence - 1);
                        }

                        long age = System.nanoTime() - frame.arrivalNanos();
                        if (age > MAX_FRAME_AGE_NANOS && incomingAudio.size() >= JITTER_TARGET_FRAMES) {
                            // Mieux vaut perdre une très vieille trame que transformer l'appel
                            // en talkie-walkie avec plusieurs centaines de ms de retard.
                            continue;
                        }

                        AudioFormat wanted = frame.format();
                        if (!ensureSpeakerFormat(wanted)) {
                            primed = false;
                            Thread.sleep(20);
                            continue;
                        }

                        SourceDataLine out = speaker;
                        if (out == null || !out.isOpen()) {
                            primed = false;
                            continue;
                        }

                        byte[] pcm = normalizeFrame(frame.data(), audioFrameBytes(wanted));
                        int written = out.write(pcm, 0, pcm.length);

                        lastPlayedAudioSequence = frame.sequence();
                        lastRemoteLevel = rms(pcm);
                        lastRemotePlaybackNanos = System.nanoTime();

                        long played = playedAudioFrames.incrementAndGet();
                        if (played == 1 || played % 100 == 0 || lastRemoteLevel > 1200) {
                            System.out.println("[Tuyage CALL] Audio fluide joué: " + played +
                                    " trame(s) • seq=" + frame.sequence() +
                                    " • niveau distant=" + Math.round(lastRemoteLevel) +
                                    " • jitter=" + incomingAudio.size() +
                                    " • pertesSeq=" + sequenceGaps +
                                    " • sortie=" + shortDevice(activeOutputDevice));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        if (active.get()) window.setWarning("Lecture audio interrompue: " + safeMessage(e));
                        break;
                    }
                }
            }, "tuyage-call-speaker");
            speakerThread.setDaemon(true);
            speakerThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
            speakerThread.start();

            try {
                microphone = openBestMicrophone();
                microphoneFormat = microphone.getFormat();
                microphone.start();

                int frameBytes = audioFrameBytes(microphoneFormat);
                System.out.println("[Tuyage CALL] Microphone prêt: " + activeInputDevice + " / " + audioFormatText(microphoneFormat));
                window.setStatus("Connecté • micro " + shortDevice(activeInputDevice) + " • " + audioFormatText(microphoneFormat));

                micThread = new Thread(() -> {
                    byte[] buffer = new byte[frameBytes];
                    long lowLevelFrames = 0;

                    while (active.get()) {
                        try {
                            TargetDataLine in = microphone;
                            if (in == null || !in.isOpen()) break;

                            int n = readExactFrame(in, buffer);
                            if (n <= 0) continue;

                            if (!muted.get()) {
                                // Toujours transmettre un PCM canonique: 48 kHz / 16 bits / mono / little-endian.
                                // Le micro Windows peut être 44.1/48 kHz et mono/stéréo; on le normalise ici.
                                byte[] captured = Arrays.copyOf(buffer, frameBytes);
                                byte[] pcmChunk = toCanonicalPcm(captured, microphoneFormat);
                                double level = rms(pcmChunk);
                                byte[] chunk = pcmChunk;
                                String codec = "";
                                OpusEncoder enc = opusEncoder;
                                if (enc != null) {
                                    short[] samples = pcmBytesToShorts(pcmChunk);
                                    byte[] encoded = new byte[OPUS_MAX_PACKET_BYTES];
                                    int encodedLen = enc.encode(samples, 0, OPUS_FRAME_SAMPLES, encoded, 0, encoded.length);
                                    chunk = Arrays.copyOf(encoded, encodedLen);
                                    codec = AUDIO_CODEC_OPUS;
                                }
                                if (level < 35.0) lowLevelFrames++; else lowLevelFrames = 0;

                                long seq = audioSequence.incrementAndGet();
                                boolean sent;
                                int rate = Math.round(CALL_AUDIO_FORMAT.getSampleRate());
                                int bits = CALL_AUDIO_FORMAT.getSampleSizeInBits();
                                int channels = CALL_AUDIO_FORMAT.getChannels();

                                if (groupId > 0) {
                                    sent = api.sendGroupMediaCall(groupId, CallPacketType.CALL_AUDIO, callId, chunk, video, seq,
                                            rate, bits, channels, codec);
                                } else {
                                    sent = api.sendMediaCall(peerId, CallPacketType.CALL_AUDIO, callId, chunk, video, seq,
                                            rate, bits, channels, codec);
                                }

                                if (sent) {
                                    long count = sentAudioPackets.incrementAndGet();
                                    if (count == 1 || count % 100 == 0) {
                                        System.out.println("[Tuyage CALL] Audio envoyé: " + count +
                                                " paquet(s) • niveau=" + Math.round(level) +
                                                " • " + audioFormatText(microphoneFormat) +
                                                " • canal=" + (api.isCallChannelRegistered() ? "READY" : "OPEN"));
                                    }
                                }

                                // Diagnostic utile : si le micro est bien ouvert mais ne capte
                                // quasiment aucune amplitude pendant ~4 s, le mauvais périphérique
                                // Windows est probablement sélectionné/muet.
                                if (lowLevelFrames == 200) {
                                    window.setWarning("Le microphone « " + shortDevice(activeInputDevice) + " » capte presque du silence. Clique sur ⚙ Audio, teste le micro et choisis le bon périphérique Windows.");
                                }
                            }
                        } catch (Exception e) {
                            if (active.get()) window.setWarning("Microphone interrompu: " + safeMessage(e));
                            break;
                        }
                    }
                }, "tuyage-call-microphone");
                micThread.setDaemon(true);
                micThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
                micThread.start();
            } catch (Exception e) {
                microphone = null;
                microphoneFormat = null;
                System.err.println("[Tuyage CALL] Microphone indisponible: " + safeMessage(e));
                window.setWarning("Microphone indisponible. L'écoute distante reste active; vérifie le micro Windows.");
            }
        }

        private TargetDataLine openBestMicrophone() throws Exception {
            TargetDataLine line = AudioDeviceUtil.openInput(inputDeviceName, CALL_AUDIO_CANDIDATES);
            activeInputDevice = AudioDeviceUtil.isAuto(inputDeviceName)
                    ? line.getLineInfo().toString()
                    : inputDeviceName;
            return line;
        }

        private void chooseAudioDevices() {
            boolean restart = active.get();
            if (restart) {
                window.setStatus("Configuration audio…");
                closeAudio();
            }

            AudioDeviceDialog.Selection selection = AudioDeviceDialog.show(
                    app.stage(), CALL_AUDIO_CANDIDATES, inputDeviceName, outputDeviceName);

            if (selection != null) {
                inputDeviceName = selection.input() == null ? AudioDeviceUtil.AUTO : selection.input();
                outputDeviceName = selection.output() == null ? AudioDeviceUtil.AUTO : selection.output();
                AUDIO_PREFS.put("callInputDevice", inputDeviceName);
                AUDIO_PREFS.put("callOutputDevice", outputDeviceName);
            }

            if (restart && active.get()) {
                playedAudioFrames.set(0);
                sentAudioPackets.set(0);
                receivedAudioPackets.set(0);
                lastRemotePlaybackNanos = 0L;
                startAudio();
            }
        }

        private boolean ensureSpeakerFormat(AudioFormat wanted) {
            if (wanted == null) wanted = CALL_AUDIO_FORMAT;
            if (speaker != null && speaker.isOpen() && sameAudioFormat(speakerFormat, wanted)) return true;

            closeSpeakerOnly();
            try {
                SourceDataLine line = AudioDeviceUtil.openOutput(outputDeviceName, wanted);
                line.start();
                speaker = line;
                speakerFormat = wanted;
                speakerFrameBytes = audioFrameBytes(wanted);
                activeOutputDevice = AudioDeviceUtil.isAuto(outputDeviceName)
                        ? line.getLineInfo().toString()
                        : outputDeviceName;
                System.out.println("[Tuyage CALL] Haut-parleur prêt: " + activeOutputDevice +
                        " / " + audioFormatText(wanted));
                window.setStatus("Connecté • sortie " + shortDevice(activeOutputDevice) +
                        " • audio reçu " + Math.round(wanted.getSampleRate()) + " Hz");
                return true;
            } catch (Exception e) {
                System.err.println("[Tuyage CALL] Sortie audio incompatible pour " + audioFormatText(wanted) +
                        ": " + safeMessage(e));
                window.setWarning("Le son distant arrive mais le haut-parleur n'a pas pu être ouvert. Clique sur ⚙ Audio et choisis Haut-parleurs/Casque.");
                return false;
            }
        }

        private String shortDevice(String name) {
            if (name == null || name.isBlank()) return "audio Windows";
            String value = name.trim();
            return value.length() <= 34 ? value : value.substring(0, 31) + "…";
        }

        private void closeSpeakerOnly() {
            SourceDataLine out = speaker;
            speaker = null;
            speakerFormat = null;
            speakerFrameBytes = 0;
            activeOutputDevice = "";
            if (out != null) {
                try { out.flush(); } catch (Exception ignored) { }
                try { out.stop(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }
        }

        private boolean sameAudioFormat(AudioFormat a, AudioFormat b) {
            if (a == null || b == null) return false;
            return Math.round(a.getSampleRate()) == Math.round(b.getSampleRate())
                    && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                    && a.getChannels() == b.getChannels()
                    && a.isBigEndian() == b.isBigEndian();
        }

        private int audioFrameBytes(AudioFormat format) {
            int frameSize = Math.max(1, format.getFrameSize());
            int frames = Math.max(1, Math.round(format.getSampleRate() * AUDIO_FRAME_MS / 1000f));
            return frames * frameSize;
        }

        private String audioFormatText(AudioFormat format) {
            return Math.round(format.getSampleRate()) + " Hz / " + format.getSampleSizeInBits() + " bits / " +
                    (format.getChannels() == 1 ? "mono" : format.getChannels() + " canaux");
        }

        private int readExactFrame(TargetDataLine in, byte[] buffer) {
            int offset = 0;
            while (active.get() && offset < buffer.length) {
                int n = in.read(buffer, offset, buffer.length - offset);
                if (n < 0) return offset;
                if (n == 0) continue;
                offset += n;
            }
            return offset;
        }

        private byte[] normalizeFrame(byte[] data, int expectedBytes) {
            int length = Math.max(2, expectedBytes);
            if (data == null || data.length == 0) return new byte[length];
            if (data.length == length) return data;
            byte[] frame = new byte[length];
            System.arraycopy(data, 0, frame, 0, Math.min(data.length, frame.length));
            return frame;
        }

        private void startVideo() {
            videoThread = new Thread(() -> {
                try {
                    webcam = Webcam.getDefault();
                    if (webcam == null) {
                        window.setWarning("Aucune webcam détectée. L'appel continue en audio.");
                        return;
                    }
                    try {
                        Dimension vga = WebcamResolution.VGA.getSize();
                        Dimension qvga = WebcamResolution.QVGA.getSize();
                        if (Arrays.asList(webcam.getViewSizes()).contains(vga)) webcam.setViewSize(vga);
                        else if (Arrays.asList(webcam.getViewSizes()).contains(qvga)) webcam.setViewSize(qvga);
                    } catch (Exception ignored) { }
                    webcam.open();
                    while (active.get()) {
                        if (!cameraEnabled.get()) {
                            sleep(160);
                            continue;
                        }
                        BufferedImage image = webcam.getImage();
                        if (image != null) {
                            byte[] jpeg = jpeg(image);
                            if (jpeg.length > 0) {
                                window.updateLocalFrame(jpeg);
                                long seq = videoSequence.incrementAndGet();
                                if (groupId > 0) api.sendGroupMediaCall(groupId, CallPacketType.CALL_VIDEO, callId, jpeg, true, seq);
                                else api.sendMediaCall(peerId, CallPacketType.CALL_VIDEO, callId, jpeg, true, seq);
                                long sent = sentVideoPackets.incrementAndGet();
                                if (sent == 1 || sent % 30 == 0) System.out.println("[Tuyage CALL] Vidéo envoyée: " + sent + " image(s)");
                            }
                        }
                        sleep(100);
                    }
                } catch (Throwable e) {
                    if (active.get()) window.setWarning("Caméra indisponible: " + safeMessage(e) + ". Si deux clients tournent sur le même PC, une seule instance peut souvent utiliser la webcam. Teste la vidéo sur deux ordinateurs.");
                } finally {
                    closeWebcam();
                }
            }, "tuyage-call-webcam");
            videoThread.setDaemon(true);
            videoThread.start();
        }

        void receiveAudio(CallPacket packet) {
            if (!active.get() || packet == null || packet.getPayload().isEmpty()) return;
            if (packet.getFromUserId() == me.getId()) return;

            long received = receivedAudioPackets.incrementAndGet();
            long seq = packet.getSequence() > 0 ? packet.getSequence() : received;
            byte[] audio = packet.getPayload().toByteArray();
            boolean opus = AUDIO_CODEC_OPUS.equalsIgnoreCase(packet.getAudioCodec());
            if (opus) {
                try {
                    OpusDecoder dec = opusDecoder;
                    if (dec == null) dec = opusDecoder = new OpusDecoder(48000, 1);
                    short[] decoded = new short[OPUS_FRAME_SAMPLES];
                    int decodedSamples = dec.decode(audio, 0, audio.length, decoded, 0, OPUS_FRAME_SAMPLES, false);
                    audio = shortsToPcmBytes(decoded, decodedSamples);
                } catch (Exception e) {
                    System.err.println("[Tuyage CALL] Trame Opus ignorée: " + safeMessage(e));
                    return;
                }
            }

            int rate = opus ? 48000 : (packet.getAudioSampleRate() > 0 ? packet.getAudioSampleRate() : 44100);
            int bits = opus ? 16 : (packet.getAudioSampleBits() > 0 ? packet.getAudioSampleBits() : 16);
            int channels = opus ? 1 : (packet.getAudioChannels() > 0 ? packet.getAudioChannels() : 1);

            // Compatibilité avec un ancien client: convertir aussi à la réception.
            // Tous les nouveaux clients jouent ensuite exactement le même format.
            AudioFormat remoteFormat = new AudioFormat((float) rate, bits, channels, true, false);
            audio = toCanonicalPcm(audio, remoteFormat);
            incomingAudio.offer(new AudioFrame(seq, System.nanoTime(), audio,
                    Math.round(CALL_AUDIO_FORMAT.getSampleRate()),
                    CALL_AUDIO_FORMAT.getSampleSizeInBits(), CALL_AUDIO_FORMAT.getChannels()));

            // Ne jamais laisser une file de plusieurs secondes se former : on jette le plus ancien.
            while (incomingAudio.size() > MAX_AUDIO_FRAMES) incomingAudio.poll();

            if (received == 1) {
                window.setWarning("");
                window.setStatus("Connecté • audio reçu " + rate + " Hz • ouverture du haut-parleur…");
            }
            if (received == 1 || received % 100 == 0) {
                System.out.println("[Tuyage CALL] Audio reçu (canal média direct): " + received +
                        " paquet(s) • buffer=" + incomingAudio.size() +
                        " • format=" + rate + " Hz/" + bits + " bits/" + channels + " ch");
            }
        }

        void receiveVideo(CallPacket packet) {
            if (!active.get() || packet == null || packet.getPayload().isEmpty()) return;
            long seq = packet.getSequence();
            if (seq > 0 && seq <= lastVideoSequence) return;
            if (seq > 0) lastVideoSequence = seq;
            User sender = userResolver.apply(packet.getFromUserId());
            String source = sender == null ? "Correspondant" : sender.getDisplayName();
            window.updateRemoteFrame(packet.getPayload().toByteArray(), source);
            long received = receivedVideoPackets.incrementAndGet();
            if (received == 1 || received % 30 == 0) System.out.println("[Tuyage CALL] Vidéo reçue: " + received + " image(s) de " + source);
        }

        private void toggleMute() {
            boolean value = !muted.get();
            muted.set(value);
            window.setMuted(value);
        }

        private void toggleCamera() {
            if (!video) return;
            boolean value = !cameraEnabled.get();
            cameraEnabled.set(value);
            window.setCameraEnabled(value);
        }

        void finishWithBusyTone(String reason) {
            if (current != this) return;
            active.set(false);
            closeAudio();
            closeWebcam();
            window.setStatus(reason);
            startBusyTone(() -> {
                if (current != this) return;
                window.close(reason);
                current = null;
            });
        }

        void finish(String reason, boolean notifyPeer) {
            if (current != this) return;
            stopRingtone();
            if (notifyPeer) {
                try {
                    if (groupId > 0) {
                        CallPacketType type = (!active.get() && outgoing) ? CallPacketType.CALL_END : CallPacketType.CALL_LEAVE;
                        api.sendGroupCall(groupId, 0, type, callId, video, reason);
                    } else api.sendCall(peerId, CallPacketType.CALL_END, callId, new byte[0], video, reason);
                } catch (Exception ignored) { }
            }
            active.set(false);
            closeAudio();
            closeWebcam();
            window.close(reason);
            current = null;
        }

        private void closeAudio() {
            if (microphone != null) {
                try { microphone.stop(); } catch (Exception ignored) { }
                try { microphone.close(); } catch (Exception ignored) { }
                microphone = null;
            }
            microphoneFormat = null;
            closeSpeakerOnly();
            incomingAudio.clear();
            if (micThread != null) micThread.interrupt();
            if (speakerThread != null) speakerThread.interrupt();
        }

        private void closeWebcam() {
            Webcam w = webcam;
            webcam = null;
            if (w != null) {
                try { if (w.isOpen()) w.close(); } catch (Exception ignored) { }
            }
            if (videoThread != null && videoThread != Thread.currentThread()) videoThread.interrupt();
        }
    }

    private static byte[] jpeg(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }


    /** Convertit PCM_SIGNED 16-bit little-endian mono/stéréo vers 48 kHz mono. */
    private static byte[] toCanonicalPcm(byte[] input, AudioFormat source) {
        if (input == null || input.length < 2 || source == null) return new byte[audioBytesForCanonicalFrame()];
        if (source.getSampleSizeInBits() != 16 || source.isBigEndian()) {
            throw new IllegalArgumentException("Format micro non supporté: PCM 16-bit little-endian requis");
        }
        int channels = Math.max(1, source.getChannels());
        int sourceFrameSize = channels * 2;
        int sourceFrames = input.length / sourceFrameSize;
        if (sourceFrames <= 0) return new byte[audioBytesForCanonicalFrame()];

        // Réduction mono avec moyenne des canaux, en int pour éviter le débordement short.
        short[] mono = new short[sourceFrames];
        for (int f = 0; f < sourceFrames; f++) {
            int sum = 0;
            int base = f * sourceFrameSize;
            for (int c = 0; c < channels; c++) {
                int i = base + c * 2;
                short v = (short) ((input[i] & 0xff) | (input[i + 1] << 8));
                sum += v;
            }
            mono[f] = (short) (sum / channels);
        }

        int targetFrames = Math.max(1, Math.round(CALL_AUDIO_FORMAT.getSampleRate() * AUDIO_FRAME_MS / 1000f));
        byte[] out = new byte[targetFrames * 2];
        double sourceRate = source.getSampleRate() > 0 ? source.getSampleRate() : CALL_AUDIO_FORMAT.getSampleRate();
        double targetRate = CALL_AUDIO_FORMAT.getSampleRate();
        for (int t = 0; t < targetFrames; t++) {
            double pos = t * sourceRate / targetRate;
            int i0 = Math.min(sourceFrames - 1, (int) pos);
            int i1 = Math.min(sourceFrames - 1, i0 + 1);
            double frac = pos - i0;
            int sample = (int) Math.round(mono[i0] * (1.0 - frac) + mono[i1] * frac);
            sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
            out[t * 2] = (byte) (sample & 0xff);
            out[t * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return out;
    }

    private static int audioBytesForCanonicalFrame() {
        return Math.max(1, Math.round(CALL_AUDIO_FORMAT.getSampleRate() * AUDIO_FRAME_MS / 1000f)) * 2;
    }

    private static short[] pcmBytesToShorts(byte[] pcm) {
        int n = pcm == null ? 0 : pcm.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) ((pcm[i*2] & 0xff) | (pcm[i*2+1] << 8));
        return out;
    }

    private static byte[] shortsToPcmBytes(short[] samples, int count) {
        int n = Math.max(0, Math.min(count, samples == null ? 0 : samples.length));
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = samples[i];
            out[i*2] = (byte) (v & 0xff);
            out[i*2+1] = (byte) ((v >>> 8) & 0xff);
        }
        return out;
    }

    private static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) sample * sample;
            count++;
        }
        return count == 0 ? 0.0 : Math.sqrt(sum / count);
    }

    private static void scalePcm16(byte[] pcm, double factor) {
        if (pcm == null) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int scaled = (int) Math.round(sample * factor);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            pcm[i] = (byte) (scaled & 0xff);
            pcm[i + 1] = (byte) ((scaled >>> 8) & 0xff);
        }
    }

    private static byte[] createTone(double frequency, int durationMs) {
        int frames = Math.max(1, (int) (CALL_AUDIO_FORMAT.getFrameRate() * durationMs / 1000.0));
        byte[] data = new byte[frames * 2];
        double sampleRate = CALL_AUDIO_FORMAT.getSampleRate();
        for (int i = 0; i < frames; i++) {
            double envelope = Math.min(1.0, Math.min(i / (sampleRate * 0.015), (frames - i) / (sampleRate * 0.02)));
            short sample = (short) (Math.sin(2.0 * Math.PI * frequency * i / sampleRate) * 9000.0 * Math.max(0.0, envelope));
            data[i * 2] = (byte) (sample & 0xff);
            data[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return data;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
