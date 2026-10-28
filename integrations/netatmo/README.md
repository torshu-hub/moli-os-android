# Netatmo Energy — lecture seule

Pilote natif portable `moli-netatmo`, API HTTPS officielle, OAuth dans le coffre
Moli. Aucun endpoint de commande. Voir [contrat, provenance, configuration,
limites de fraîcheur et validation](../../docs/components/netatmo-energy.md).

Les fixtures `homesdata.json` et `homestatus.json` sont **synthétiques**, écrites
pour ce pilote à partir des schémas officiels consultés le 25/10/2026. Elles ne
proviennent d'aucun compte. Elles sont rejouées par les tests Rust du pilote
(le contrôleur de catalogue vérifie leur présence, sans réseau).

Implémenté : lecture topologie/états, température/consigne/modes/programme
sélectionné, booléen `boiler_status` NATherm1/OTM distinct du pourcentage de vannes,
erreurs/fraîcheur, rotation OAuth chiffrée, inventaire et carte Maison sans commandes.
À qualifier : compte réel, variantes non documentées, date physique si un modèle
la fournit, parcours de consentement et coffre sur Android. Pas de preuve matérielle.
