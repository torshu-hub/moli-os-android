# Prochain incrément : observer Netatmo → Meross sans agir

Code32 livre d’abord les autorisations Meross indépendantes. Aucun observateur
ni automatisme de chauffage n’est créé sur la tablette. Le cadrage code31 est
complété par un prototype de qualification Rust, sans nouvelle API ou moteur.

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

## Qualification portable ajoutée avec code32

`crates/moli-automation/tests/netatmo_meross_observation.rs` construit un graphe
avec les nœuds officiels Manual / If / Set et appelle `run_now(..., dry=true, ...)`.
Il sélectionne explicitement des appareils synthétiques et leur modèle dans un
snapshot du hub. Un brouillon désactivé est créé uniquement en mémoire du test ;
aucun graphe n'est installé dans une instance utilisateur. Les comparaisons de
fraîcheur portent sur la collecte, avec une limite de 300 s propre à la fixture.

Matrice qualifiée, avec cible autorisée **et** cible en lecture seule : demande
textuelle on/off/unknown, null/type incorrect, collecte ancienne/future/absente,
source hors ligne, cible absente, répétition de l'essai. Seules on/off connues et
fraîches produisent un pas `Simulated`. Zéro commande reçue par le pilote espion,
zéro `Action::Command` dans le journal et aucune demande d'approbation. Les
permissions manuelles ne sont ni requises ni modifiées pour la simulation.

Un détail de l'amont est maintenant testé : les comparaisons générales de Moli
assimilent volontairement OFF à false. L'admission d'une observation Netatmo doit
donc vérifier le **type texte et l'enum déclaré**, sans accepter un booléen publié
par erreur sous ce point. Ne pas changer la sémantique générale des automatismes.

**Prochain lot déjà borné :** parcours de sélection source/cible et observation
serveur, distinct des habilitations manuelles ; admission stricte et fraîcheur
configurée par le pilote (pas les 300 s de la fixture), cohérence avant/après
l'exécution à blanc, gestion INCONNU/PÉRIMÉ/coupure/reprise et réduction des
répétitions dans le rapport existant. Le prototype ne fournit pas encore ces
sélecteurs, une persistance utilisateur, un observateur ou des diagnostics de
transition sur la S5e. Ne pas confondre sa qualification avec une liaison active.
