# Prochain incrément : observer Netatmo → Meross sans agir

Cadrage seulement : code31 ne crée aucun automatisme ni observateur actif.
La priorité est de livrer les accès simplifiés aux commandes manuelles validées.

## Réutilisation Moli

Le moteur `moli-automation` possède déjà les règles `Rule::State`, `Rule::Stale`
et `Automations::run_now(..., dry=true, ...)`. Dans `run::act`, le mode à blanc
retourne `StepStatus::Simulated` avant tout appel à `hub.command`. Réutiliser ce
mécanisme et sa trace, les états publiés et les diagnostics compacts existants ;
aucun moteur de règles Java/JavaScript ni calcul de thermostat supplémentaire.
La variante Meross manuelle continue de refuser `Origin::Automation` : la
préparation d'un scénario ne doit jamais retirer ce verrou.

## Petit lot proposé et bornes à prouver

Choisir explicitement une source Netatmo et une cible Meross déjà associées,
sans les renommer ni modifier leurs habilitations. Lire exclusivement la demande
de chauffe déclarée déjà publiée : `declared_demand` vaut `on`, `off` ou
`unknown` ; le champ source `boiler_status`, lorsqu’il est présent, est booléen.
Ne pas déduire une demande des températures.
Évaluer à blanc sur un snapshot cohérent du hub. La trace décrit une intention,
jamais une commande confirmée ni un état physique. Aucun graphe activé, aucune
relance de commande, aucun minuteur Finder/Meross ou régulation concurrente.

| Entrée observée | Résultat à tester | Effet matériel |
|---|---|---|
| Demande déclarée fraîche `on` | Intention simulée ON | Aucun |
| Demande déclarée fraîche `off` | Intention simulée OFF | Aucun |
| Demande `unknown`, absente, null ou de type inattendu | INCONNU, aucune intention | Aucun |
| Source hors ligne, pilote arrêté ou horodatage absent/futur/périmé | DONNÉES_PÉRIMÉES, aucune intention | Aucun |
| Même état frais répété | Pas de répétition excessive du diagnostic | Aucun |
| Reprise après inconnu/périmé, changement de source ou redémarrage | Nouvelle observation justifiée, aucun rattrapage d'ordre | Aucun |

Le contrat de fraîcheur doit reprendre celui du pilote Netatmo, y compris la
date réelle de collecte, pas seulement l'arrivée d'un snapshot dans l'interface.
La sélection source/cible, l'évaluation cohérente et les états à trois valeurs
ne sont pas couverts par l'ajout d'un simple bouton dry-run ; ils justifient
un incrément ciblé distinct de l'ergonomie code31.

Tests nécessaires pour ce prochain lot : graphe synthétique avec les cas du
tableau, hub espion prouvant zéro appel à `command`, zéro SET dans le serveur
factice, conservation des associations et des autorisations après redémarrage,
trace compacte sans noms privés/UUID/IP/valeurs fournisseur. Le journal et
l'export d'essai restent ceux de Moli, sans seconde collecte parallèle.

La limite de 30 minutes configurée dans Meross est conservée, mais son autonomie
hors Internet n'est pas qualifiée. Aucune automatisation chaudière/Finder ni
commande physique n'est autorisée par ce cadrage.
