# Patch 3.0.2 — démarrage client

- Ajout de `com.tuyage.client.ClientLauncher` avec un vrai `main(String[] args)`.
- Configuration du plugin JavaFX sur `ClientLauncher`.
- `run-client.bat` utilise `call`, affiche les erreurs et reste ouvert après fermeture/échec.
- La commande manuelle recommandée est désormais :
  `mvn javafx:run`
  ou
  `mvn exec:java -Dexec.mainClass=com.tuyage.client.ClientLauncher`
