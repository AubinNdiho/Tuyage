# Patch 3.0.14 — appels audio Opus

- PCM réseau remplacé par Opus lorsque le codec est disponible.
- Capture normalisée en PCM 48 kHz / 16 bits / mono avant encodage.
- Trames Opus de 20 ms (960 échantillons), mode VOIP, VBR, 32 kbit/s.
- FEC Opus activée avec hypothèse de 10 % de perte.
- `audio_codec = "opus"` ajouté à `CallPacket`.
- Compatibilité conservée : un client/paquet sans `audio_codec` continue à être lu comme PCM.
- Décodage Opus effectué avant le jitter buffer et la sortie Java Sound.

Le serveur gRPC ne décode pas le média : il route les paquets Opus tels quels.
