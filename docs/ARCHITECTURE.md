# Architecture de Tuyage 3.0 FINAL TP

## Vue d'ensemble

```text
PC CLIENT(S) JavaFX
        |
        | gRPC + Protocol Buffers / TCP 9090
        v
Serveur Tuyage Java 21
        |
        +-- Authentification / profils / contacts / blocage
        +-- Messagerie temps réel + événements Subscribe
        +-- Groupes + rôles
        +-- Statuts 24 h + vues/commentaires
        +-- Signalisation + média des appels
        |
        v
H2 persistante : data/tuyage.mv.db
```

Le fichier `src/main/proto/tuyage.proto` constitue le contrat unique entre le client et le serveur.

## Flux temps réel

- RPC synchrones : inscription, connexion, CRUD profil/groupes/statuts, historique messages.
- `Subscribe(AuthRequest) returns (stream Event)` : messages, présence, lecture, réactions, typing, appels, groupes et statuts.
- `Call(stream CallPacket) returns (stream CallPacket)` : canal média gRPC ; le routage temps réel est relayé uniquement vers les participants concernés.

## Persistance

H2 conserve comptes, profils, groupes, messages, réactions, masquages locaux, épingles, contacts, blocages, statuts, vues, commentaires, historique d'appels et lectures de groupe.

## Sécurité TP

- mots de passe PBKDF2 + sel individuel ;
- token de session UUID ;
- contrôles d'autorisation côté serveur ;
- blocage appliqué à la messagerie, aux appels, à la présence et aux statuts ;
- taille média limitée à 20 Mo ;
- récupération par OTP de démonstration (5 minutes, 5 essais). En production, brancher un fournisseur SMS.

## Appels

L'audio utilise PCM mono 44,1 kHz / 16 bits en trames d'environ 20 ms, numérotées. Le client possède un petit jitter buffer et abandonne les trames trop anciennes pour limiter la latence. Pour la vidéo : flux JPEG webcam, vue distante principale et vignette locale.

Pour une démonstration fiable : deux PC + écouteurs/casques. Cela réduit l'écho acoustique haut-parleur -> microphone.
