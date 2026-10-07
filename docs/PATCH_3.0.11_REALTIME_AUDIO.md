# Tuyage 3.0.11 — Real-time audio routing / jitter fix

Cette version corrige le cas observé sur deux PC où l'appel est accepté,
le micro capte la voix et le canal gRPC est READY, mais aucun son n'est audible.

## Deux causes corrigées

1. Le serveur routait le média seulement si `SessionRegistry.isOnline(...)`
   était vrai. Une micro-reconnexion du flux `Subscribe` pouvait donc jeter
   l'audio alors que le canal média dédié était toujours enregistré.

2. Le lecteur audio avançait toutes les 20 ms même sans paquet reçu.
   Il injectait alors des trames de silence et incrémentait la séquence attendue.
   Les vrais paquets arrivés légèrement plus tard devenaient ensuite "anciens"
   et étaient jetés. Les logs pouvaient montrer des milliers de `Audio joué`
   avec `niveau distant=0`.

## Nouveau comportement

- Le média d'appel est routé directement via `CallRegistry`.
- Le lecteur attend de vraies trames.
- En cas de trou réseau, il rebufferise quelques trames au lieu d'inventer
  du silence.
- Les logs `Audio réel joué` correspondent maintenant à de vrais paquets reçus.

Aucun changement n'a été fait au projet NewGB.
