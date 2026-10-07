# Tuyage 3.0 FINAL TP — Notes de version

Cette version consolide le projet de messagerie gRPC autour d'une démonstration complète sur un ou deux PC.

## Points forts ajoutés/consolidés

- comptes par numéro de téléphone, carte **Moi**, contacts, blocage et récupération de mot de passe par OTP de démonstration ;
- messages texte/médias/vocaux, réponse, édition, transfert, copie, réactions, épinglage, recherche, suppression pour soi/pour tous ;
- état `✓ / ✓✓ / ✓✓ bleu`, messages hors ligne marqués livrés à la reconnexion et compteurs non lus persistants ;
- galerie des médias d'une conversation, export texte et effacement local de conversation ;
- groupes owner/admin/member, gestion des membres et appels de groupe avec **Rejoindre / Ignorer** ;
- les flux média d'un appel de groupe sont envoyés seulement aux participants qui ont rejoint ;
- quand le dernier participant quitte un appel de groupe, l'historique est clôturé proprement ;
- statuts 24 h texte/image/vidéo, vues, liste des viewers, commentaires et visibilité limitée aux contacts non bloqués ;
- appels audio/vidéo directs, sonnerie personnalisée, ringback lent, busy tone, historique persistant, audio faible latence et vidéo distante en grand ;
- présence plus robuste lors d'une fermeture brutale/reconnexion ;
- notifications natives Windows, conversation épinglée/muet/archivée, thème clair/sombre et fond personnalisé ;
- scripts de vérification réseau et documentation de démonstration.

## Limites assumées du TP

- L'OTP de récupération est un **OTP de démonstration** : il est affiché par le serveur/l'application. Un vrai produit utiliserait un fournisseur SMS externe.
- L'audio/vidéo est conçu pour le TP gRPC. Le meilleur résultat est obtenu sur **deux PC avec casque/écouteurs**, afin d'éviter l'écho acoustique et les conflits de webcam.
- L'installateur Windows / `Tuyage.exe` sera construit **après validation fonctionnelle de la v3**, afin de ne pas reconstruire l'installateur à chaque correction.
