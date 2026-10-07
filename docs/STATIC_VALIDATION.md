# Validation statique de la livraison

Contrôles effectués avant génération du ZIP :

- `pom.xml` et `nbactions.xml` : XML valide ;
- contrat `tuyage.proto` : 47 RPC déclarés et chacun possède une implémentation dans `ChatServiceImpl` ;
- numéros de champs protobuf : aucun doublon détecté ;
- analyse syntaxique des sources Java : aucune erreur de syntaxe détectée (les dépendances Maven ne sont pas présentes dans l'environnement de fabrication, donc le build Maven complet doit être validé dans NetBeans) ;
- ressources obligatoires : logo Tuyage et sonnerie entrante présents ;
- aucun dossier `target/` ou `data/` n'est livré dans l'archive afin de fournir un projet propre.

Validation finale attendue sur le PC du TP : **NetBeans > Clean and Build > BUILD SUCCESS**, puis exécution de `docs/FINAL_CHECKLIST.md`.
