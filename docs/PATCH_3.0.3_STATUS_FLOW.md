# Tuyage 3.0.3 — Statuts enchaînés

Cette version améliore la consultation des statuts :

- séparation **Mon statut / Mises à jour récentes / Mises à jour vues** ;
- l’état vu/non-vu est fourni par le serveur et persiste dans H2 ;
- ouverture au premier statut non vu d’un contact ;
- enchaînement automatique de tous les statuts d’un contact puis du contact suivant ;
- retour automatique à l’accueil après le dernier contact ;
- barres de progression par statut ;
- texte et image : passage automatique après 7 secondes ;
- vidéo : lecture intégrée et passage à la fin de la vidéo ;
- clic gauche/droite ou flèches clavier pour précédent/suivant ;
- Échap pour quitter ;
- pause/reprise ;
- réponse au statut sans perdre la position ;
- anneau distinct pour les statuts déjà vus.

Le fichier `StatusUpdate` contient désormais `viewed_by_me`, calculé côté serveur à partir de `status_views`.
