# Tuyage 3.0.10

- Statuts : flèches gauche/droite au clavier pour naviguer.
- Barre d’espace : pause/reprise du statut (hors champ de saisie).
- Suppression du titre Tuyage noir dupliqué sous le logo.

# Tuyage 3.0 FINAL TP

**Tuyage** est une application desktop de messagerie instantanée réalisée en **Java 21 + JavaFX + gRPC/Protocol Buffers + H2**. Le projet est Maven et s'ouvre directement dans NetBeans.

L'objectif de cette version FINAL TP est de proposer une expérience proche d'une messagerie moderne tout en gardant une identité visuelle propre à Tuyage.

## Fonctions incluses

### Comptes, contacts et profil

- création et connexion par **numéro de téléphone** ;
- mot de passe sécurisé côté serveur par PBKDF2 ;
- carte **Moi** visible avec photo, nom et numéro ;
- modification du nom, bio et photo ;
- remplacement ou retrait de la photo ;
- changement du mot de passe ;
- **mot de passe oublié** avec OTP de démonstration valable 5 minutes (prévu pour être remplacé par un vrai SMS en production) ;
- suppression définitive du compte ;
- ajout/retrait de contacts par numéro ;
- liste des contacts ;
- blocage/déblocage d'un utilisateur ;
- présence `En ligne` et dernière présence.

### Discussions

- texte et emojis ;
- image, vidéo, audio et fichiers ;
- enregistrement vocal visible ;
- pendant un vocal : **Entrée = arrêter + envoyer immédiatement**, Échap = annuler ;
- réponse à un message ;
- modification de ses messages texte ;
- suppression **pour moi** ou **pour tout le monde** ;
- transfert vers un utilisateur ou un groupe ;
- copie du texte ;
- réactions emoji ;
- épinglage d'un message ;
- recherche dans la conversation ;
- indicateur `écrit...` et `enregistre un audio...` ;
- `✓` envoyé, `✓✓` livré et `✓✓` bleu lu ;
- badges de messages non lus **persistants après reconnexion/redémarrage client** ;
- notifications JavaFX + notifications natives Windows quand l'application est derrière une autre fenêtre ;
- conversation épinglable, archivable et mise en sourdine via clic droit ;
- écran **Archives** pour restaurer une conversation ;
- galerie **Médias, liens et fichiers** dans chaque conversation ;
- export d’une conversation en fichier `.txt` ;
- effacement complet d’une conversation **uniquement pour soi**.

### Groupes

- création avec nom, description et photo ;
- propriétaire, administrateurs et membres ;
- promotion/rétrogradation d'un administrateur par le propriétaire ;
- ajout/retrait de membres selon les droits ;
- modification du groupe par propriétaire/admin ;
- présence des membres en ligne ;
- quitter le groupe ;
- suppression par le propriétaire ;
- messages et médias dans les groupes ;
- appels audio/vidéo de groupe : chaque membre reçoit l'invitation et choisit **Rejoindre** ou **Ignorer** ;
- seuls les membres ayant effectivement rejoint reçoivent le flux audio/vidéo de groupe.

### Statuts 24 h

- statut texte avec fonds différents ;
- image ou vidéo depuis la galerie ;
- expiration automatique côté serveur après 24 h ;
- navigation automatique entre statuts ;
- barre de progression ;
- modification d'un statut texte personnel ;
- suppression d'un statut personnel ;
- commentaires/réponses ;
- compteur de vues ;
- liste des personnes ayant vu son statut ;
- les statuts sont visibles par les contacts et masqués aux utilisateurs bloqués.

### Appels audio et vidéo

- appel entrant avec **Accepter / Refuser** ;
- sonnerie entrante WAV personnalisée fournie pour le projet ;
- côté appelant : tonalité lente `Dooo........ Dooo........` ;
- tonalité `tu-tu-tu-tu` en cas de refus/occupé/indisponible ;
- bouton raccrocher toujours accessible ;
- historique persistant : entrant, sortant, refusé, manqué, occupé, indisponible et durée ;
- audio réel bidirectionnel gRPC ;
- trames PCM d'environ **20 ms**, numéros de séquence, petit jitter buffer et rejet des paquets trop anciens ;
- garde anti-écho légère ;
- reconnexion automatique du flux temps réel après une coupure temporaire ;
- présence améliorée : fermeture brutale détectée avec courte période de grâce avant passage hors ligne ;
- vidéo : correspondant en grande zone et caméra locale en petite vignette ;
- micro/caméra activables ou désactivables ;
- appels audio/vidéo de groupe.

> Pour les appels, le meilleur test est réalisé sur **deux PC avec casque/écouteurs**. Deux clients sur le même PC peuvent se disputer le même micro ou la même webcam, et les haut-parleurs peuvent créer un écho acoustique repris par le micro.

### Personnalisation

- identité visuelle **Tuyage** et logo fourni ;
- thème clair/sombre ;
- fond des conversations **neutre par défaut** ;
- bouton **Fond** pour choisir sa propre image ;
- préférence de fond enregistrée sur le PC client.

## Architecture

```text
TuyageGrpcMessenger/
├── pom.xml
├── run-server.bat
├── run-client.bat
├── find-server-ip.bat
├── test-server-connection.bat
├── check-client-pc.bat
├── START_HERE.txt
├── docs/
├── README.md
└── src/main/
    ├── proto/tuyage.proto
    ├── java/com/tuyage/server/
    │   ├── ServerMain.java
    │   ├── ChatServiceImpl.java
    │   ├── Database.java
    │   └── ...
    ├── java/com/tuyage/client/
    │   ├── ClientApp.java
    │   ├── MainView.java
    │   ├── CallController.java
    │   ├── CallWindow.java
    │   └── ...
    └── resources/
        ├── css/
        ├── images/
        └── sounds/
```

## Ouvrir dans NetBeans

1. Décompresser le ZIP.
2. Dans NetBeans : **File > Open Project**.
3. Sélectionner `TuyageGrpcMessenger`.
4. Faire **Clean and Build**.
5. Lancer d'abord le serveur, ensuite un ou plusieurs clients.

## Lancer le serveur

Le plus simple : double-cliquer sur :

```text
run-server.bat
```

Le script cherche d'abord Maven dans le PATH, puis essaie automatiquement le Maven fourni avec une installation NetBeans présente dans `C:\Program Files\NetBeans-*`.

Commande manuelle :

```cmd
cd CHEMIN\TuyageGrpcMessenger
mvn exec:java -Dexec.mainClass=com.tuyage.server.ServerMain
```

Vérifier le port :

```cmd
netstat -ano | findstr :9090
```

Résultat attendu : `LISTENING`.

## Lancer le client

Double-cliquer sur :

```text
run-client.bat
```

ou :

```cmd
mvn javafx:run
```

### Même ordinateur

Adresse serveur :

```text
localhost:9090
```

### Deux ordinateurs

Les deux PC doivent être sur le même Wi-Fi/hotspot.

Sur le PC serveur :

```cmd
ipconfig
```

Repérer l'IPv4, par exemple `192.168.137.224`.

Sur le PC client, tu peux d’abord double-cliquer `check-client-pc.bat` pour vérifier Java/Maven/réseau, ou directement `test-server-connection.bat`. Vérification manuelle :

```powershell
Test-NetConnection 192.168.137.224 -Port 9090
```

Si `TcpTestSucceeded : True`, saisir dans Tuyage :

```text
192.168.137.224:9090
```

Le client ne doit pas lancer `ServerMain`.

## Base de données

Le serveur crée automatiquement :

```text
data/tuyage.mv.db
```

Ne supprime pas `data` si tu veux conserver comptes, messages, groupes, statuts et historique d'appels.

Les nouvelles tables de la v3 (`contacts`, `blocks`, `message_hidden`, `message_pins`, `message_reads`, `status_views`) sont créées automatiquement au démarrage.

## Ressources personnalisables

Logo :

```text
src/main/resources/images/logo.png
```

Sonnerie de l'appelé :

```text
src/main/resources/sounds/incoming-ringtone.wav
```

Fond personnel : sélectionné dans l'application via **Fond** et mémorisé dans :

```text
%USERPROFILE%\.tuyage-client.properties
```

## Démonstration conseillée

Pour une présentation courte et impressionnante :

1. créer deux comptes par numéro sur deux PC ;
2. montrer la carte **Moi** et la présence en ligne ;
3. ajouter un contact ;
4. envoyer un message → notification → `✓✓` bleu après lecture ;
5. répondre/transférer/épingler/rechercher un message ;
6. enregistrer un vocal et appuyer sur **Entrée** pour l'envoyer ;
7. créer un groupe, nommer un admin et envoyer un média ;
8. publier un statut et montrer les vues ;
9. faire un appel audio réel ;
10. faire un appel vidéo sur deux webcams ;
11. montrer la trace persistante de l'appel dans le chat ;
12. ouvrir **Médias, liens et fichiers** puis exporter la conversation ;
13. tester `Mot de passe oublié ?` avec le code OTP TP ;
14. fermer/revenir pour montrer que les données et badges non lus sont conservés.

Les détails des nouveautés et limites assumées du TP sont aussi dans `docs/RELEASE_NOTES_3.0.md`.

## Étape suivante après validation

Une fois la v3 validée fonctionnellement, la dernière étape prévue est de créer un **installateur Windows / Tuyage.exe** afin que le PC client n'ait plus besoin de Maven ou NetBeans pour lancer l'application.

## Tuyage 3.0.5 - appels et archivage

- **Archiver une conversation** : dans Chats, clic droit sur le contact/groupe puis `▣ Archiver`. Pour la retrouver, cliquer sur `▣ Archives` en haut puis `Restaurer`.
- **Appel entrant** : écran dédié avec identité de l'appelant, type d'appel et gros boutons `Décrocher` / `Refuser` (ou `Rejoindre` / `Ignorer` en groupe). `Entrée` décroche et `Échap` refuse.
- **Voix** : buffers audio raccourcis, jitter cible ~40 ms, file limitée ~120 ms, gain léger du micro et garde anti-écho moins agressive. Le transport reste PCM 44,1 kHz / 16 bits / mono / trames de 20 ms.
- Pour la meilleure qualité, tester les appels sur **deux PC** et utiliser un casque/écouteurs afin d'éviter le retour acoustique haut-parleur → micro.


## v3.0.8 - Correctif canal média gRPC
- Correction de `Cannot alter onCancelHandler after initialization`.
- Le handler d’annulation du flux bidirectionnel `Call` est maintenant installé au moment légal du cycle gRPC, avant retour de la méthode de service.
- Le registre média ne tente plus de modifier le `ServerCallStreamObserver` lors de `CALL_REGISTER`.
- Résultat attendu : le canal ne se reconnecte plus en boucle et `Audio reçu (canal média direct)` apparaît réellement chez le correspondant.


## v3.0.10 - Sélection micro / haut-parleur et diagnostic de lecture

Cette version cible le cas où le canal gRPC est READY et où les paquets audio arrivent, mais aucun son n'est audible.

- bouton **⚙ Audio** directement dans la fenêtre d'appel ;
- choix explicite du microphone Windows ;
- choix explicite du haut-parleur / casque Windows ;
- test microphone 2 secondes avec niveau RMS ;
- test haut-parleur avec tonalité ;
- mémorisation des périphériques choisis ;
- support de microphones mono **et stéréo** ;
- sortie audio ouverte sur le périphérique choisi ;
- diagnostic `Audio joué` pour confirmer que les octets sont réellement écrits dans la carte son ;
- le flux gRPC média v3.0.9 reste inchangé.

Pour un micro correct, parle pendant le test et choisis un périphérique dont le niveau monte clairement (souvent plusieurs centaines ou milliers).

## v3.0.9 - Correctif appel audio silencieux

Cette version corrige spécifiquement le cas où l'appel s'établit mais où les deux utilisateurs n'entendent aucune voix.

- le microphone n'est plus forcé uniquement en PCM 44,1 kHz : Tuyage essaie automatiquement 48 kHz, 44,1 kHz, 32 kHz, 24 kHz, 22,05 kHz, 16 kHz puis 8 kHz ;
- le format PCM réellement ouvert sur le PC émetteur est transmis dans chaque paquet audio gRPC ;
- le PC destinataire ouvre sa sortie audio avec le format réellement reçu ;
- la lecture du son distant reste active même si le microphone local ne peut pas être ouvert ;
- le canal média gRPC renvoie maintenant `MEDIA_READY` après l'enregistrement côté serveur ;
- si le canal média tombe, le client le recrée automatiquement avant de continuer à envoyer les trames ;
- la console affiche désormais clairement `Canal média gRPC enregistré`, `Audio envoyé`, `Audio reçu` et le niveau du microphone ;
- si le microphone est ouvert mais ne capte quasiment que du silence pendant plusieurs secondes, la fenêtre d'appel affiche un diagnostic Windows.

### Validation recommandée

Pour l'appel réel, lancer le serveur sur le PC 1 puis un client sur chacun des deux PC. Utiliser de préférence des écouteurs/casques. Après `Décrocher`, vérifier dans les consoles clientes :

```text
[Tuyage CALL] Canal média gRPC enregistré côté serveur.
[Tuyage CALL] Microphone prêt: ...
[Tuyage CALL] Audio envoyé: 1 paquet(s) ...
[Tuyage CALL] Audio reçu (canal média direct): 1 paquet(s) ...
[Tuyage CALL] Haut-parleur prêt: ...
```

Si `Audio envoyé` apparaît mais jamais `Audio reçu`, le problème est réseau/canal média. Si `Audio reçu` apparaît mais aucun son n'est audible, la ligne `Haut-parleur prêt` ou l'avertissement de la fenêtre indique le périphérique à vérifier.
