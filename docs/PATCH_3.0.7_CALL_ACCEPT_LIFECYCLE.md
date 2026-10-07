# Tuyage 3.0.7 — correctif Décrocher

- L'acceptation d'un appel ne ferme plus la session si le canal média gRPC se reconnecte.
- La fenêtre CallWindow est explicitement maintenue au premier plan après Décrocher.
- L'écran IncomingCallWindow crée d'abord la fenêtre d'appel connecté avant de se masquer.
- Une interruption média affiche un avertissement de reconnexion au lieu de terminer l'appel.
- Le premier paquet audio reçu efface l'avertissement de reconnexion.
