# Beta2 code19 — synchronisation ciblée et configuration native

Base code18 : `5133da5c298221040f3b7c9cbbe50720db5dbdf5`.
Amont rafraîchi : `rudy-io/moli-os@1d9c8c2a9984a038fd71d53499dc06c4046b04b0`.

Phase A : portage ciblé de `c627b87` / PR89 : `Event::State.pulse`, émission
runtime, consommateurs énergie/historique et sémantique des impulsions du
moteur d'automatismes, tests amont associés. `Action::Diagnostic` conservée.
Pas d'import Tuya ni de fusion globale. Vite8/greffonSvelte7 (PR81) différés :
aucun besoin pour ce jalon et pas de migration de chaîne Web nécessaire.
SpeakerCard (PR90), nouveaux drivers et PR ouvertes100/101 différés hors périmètre.

Phase B : voir [automatismes](automatismes.md#android-beta-2-code19).
Composants officiels adaptés uniquement dans le fork : core/event, runtime/Hub,
automation/engine et sauvegarde conditionnelle, consommateurs energy/history,
API automations et suivi de révision dans l'éditeur Web. Aucune logique
Netatmo→Meross ajoutée aux pilotes. Netatmo30s/300s et tolérance code18 inchangés.
Android ajoute le formulaire natif et l'accès à la même règle dans le WebView.
Aucune contribution amont ni commande physique par Codex.
