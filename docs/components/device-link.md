# Automatisation simple Netatmo → Meross — code37

Dans **Mes appareils → Configurer la règle**, choisir le thermostat et la prise,
puis activer l’interrupteur. La demande Netatmo booléenne ON/OFF est appliquée à
la prise choisie. La demande et l’état Meross relu restent visibles. Il n’y a ni
thermostat logiciel, observation à blanc, questionnaire, ni confirmation par événement.

La règle est un scénario Moli ordinaire : `when_state(collected_at)`, `every(1 min)`,
`if` et `set`, mode `restart` (la demande la plus récente remplace le run précédent). L’activation humaine approuve sa version exacte une
fois et applique la demande courante si elle est disponible. Le moteur existant
suit ensuite les publications et sa propre horloge ; aucun scheduler ou polling
fournisseur supplémentaire. L’interrupteur arrête la règle et ses runs, sans
ordonner OFF à une prise déjà ON. Le contrôle manuel de la fiche reste disponible.

La variante Meross manuelle reste requise sur la cible. Son transport existant
vérifie l’identité, l’état, le SET signé, l’ACK et la relecture ; un état déjà
conforme évite le SET. La permission de ce run est bornée au point/à la valeur,
à six secondes et à la version activée. Le prédicat de source est revérifié à la
frontière du transport ; données absentes/inconnues/périmées, pilote arrêté,
source hors ligne ou permission cible manquante ne produisent aucun nouvel ordre.
Aucun scénario de panne avancé ni extinction implicite n’est ajouté.

La configuration vit dans `automations.json`, avec les approbations et la
persistance natives. L’ancienne association est relue comme choix initial, jamais
comme activation. Les anciens brouillons restent dans les données ; le marqueur
historique `moli_daily_heating_locked_v1` reste neutralisé pour compatibilité,
sans écran ou exécuteur d’observation. Les autres automatismes, comptes, clés,
profils et réglages sont conservés.

`GET /api/device-link` expose les choix, la version, l’état activé/attente/désactivé,
la demande et la relecture. `PUT` prend `source`, `target`, `enabled`, `fingerprint`
et exige la session humaine originale. Android la garde dans son processus natif
et la ferme après enregistrement. `POST …/refresh` réutilise la lecture volontaire
bornée code36 ; cadence Netatmo ordinaire 120 s et rythme Meross inchangés.

Les anciennes API `/api/observations/netatmo-meross`, leurs écrans, tests et
fixtures de démonstration sont supprimés. Les fonctions génériques du moteur,
son test à blanc standard et leurs tests partagés restent présents.

## Journal indépendant

**Journal d’état** et **export TXT** sont conservés, accessibles depuis l’accueil,
les fiches et la page de règle. `TrialJournalActivity`, `TrialLog`, `TrialDiagnostics`,
`ReportExport` et les moniteurs techniques restent partagés ; leurs formats et
les anciennes catégories permettent toujours de lire l’historique. Aucune nouvelle
infrastructure de traces. Ce journal sert à tous les équipements, pas au seul chauffage.

Développement : essai ciblé sur faux pilotes ON/OFF, données indisponibles et
arrêt ; connexion native authentifiée. Les preuves hors périmètre sont réutilisées,
puis les workflows obligatoires produisent et signent la bêta. Aucun accès S5e ou
SET physique par Codex. Réception réelle par le propriétaire, installation par-dessus
sans désinstallation ni effacement.
