# Tuyage 3.0.9 — correctif appel audio silencieux

But : corriger l'appel qui passe bien en état « Connecté » alors que les utilisateurs n'entendent aucune voix.

## Modifications

1. Sélection automatique d'un format microphone réellement supporté par Windows.
2. Métadonnées audio dans `CallPacket` : fréquence, profondeur et nombre de canaux.
3. Sortie distante ouverte selon le format réellement émis par l'autre PC.
4. Capture et lecture totalement indépendantes : une panne micro n'empêche plus d'entendre le correspondant.
5. Accusé `MEDIA_READY` du serveur après `CALL_REGISTER`.
6. Recréation du flux média si celui-ci n'est plus disponible au moment d'envoyer.
7. Diagnostics console et détection d'un microphone qui ne capte quasiment aucun signal.

## Test attendu sur deux PC

- les deux consoles affichent `Canal média gRPC enregistré côté serveur.` ;
- en parlant sur PC 1, PC 1 affiche `Audio envoyé` et PC 2 affiche `Audio reçu` ;
- en parlant sur PC 2, le même test fonctionne dans l'autre sens ;
- les deux côtés affichent `Haut-parleur prêt` dès les premières trames reçues.
