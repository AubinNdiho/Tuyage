# Tuyage 3.0.12 — Phone Audio Smooth

Cette version part de la v3.0.11 où le son bidirectionnel est enfin audible.

## But
Supprimer l’effet « talkie-walkie / Motorola » causé par les micro-réamorçages du jitter buffer.

## Corrections
- jitter buffer initial porté à ~100 ms ;
- pas de rébufferisation à chaque file momentanément vide ;
- rébufferisation seulement après une vraie sous-alimentation (~140 ms) ;
- aucune trame de silence artificielle ;
- abandon contrôlé des trames vraiment trop anciennes pour garder l’appel proche du temps réel ;
- tampon de sortie ramené vers ~120 ms ;
- priorité légèrement renforcée pour les threads micro et haut-parleur ;
- diagnostics `Audio fluide joué` avec niveau distant, profondeur jitter et trous de séquence.

NewGB n’a pas été modifié.
