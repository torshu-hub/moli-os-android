# MSS310 — bêta manuelle à activation explicite

Le profil canonique `meross-mss310` reste en lecture seule. Les installations
existantes et leurs quatre prises ne deviennent pas commandables à la mise à jour.
Une seule prise peut être autorisée dans Mes appareils Android, après confirmation
locale du nom, de l’adresse et de l’UUID. Aucun compte cloud ou nouvel appairage.
Netatmo code29 (lecture, cadence 120 s, âge et renouvellement) reste inchangé ;
son endurance n’est pas requalifiée par ce lot. Aucun automatisme Netatmo/Finder.

## Écart minimal après la simulation 99f1a0c

| Mécanisme original | Extension et motif | Impact amont |
|---|---|---|
| Paquet MSS310 et profil déclaratif | Variante connue `meross-mss310-manual`, profil canonique + `manual-write.toml` | Même `meta.id`, catalogue, fixtures et codec ; aucun nouveau paquet, pilote natif ou format parallèle |
| Configuration Android + concordance + coffre Moli | `manual_control` absent/faux par défaut, une sélection maximum ; `manual_uuid` distinct de l’association existante | Adaptateur Android uniquement ; ni clé ni `uuid`/`expected_uuid` existants remplacés |
| `hub.command`, garde, journal et échéance | `CommandRequest.origin` transmis au pilote ; variante manuelle limitée aux origines `Ui`/`Household` | Petit ajout générique au contrat runtime ; les autres pilotes gardent leur comportement |
| Diagnostics typés déjà présents | Quatre étapes de commande additives, réduites dans le rapport d’essai existant | Aucun nouveau journal/export ou texte fournisseur ; compteurs, durées, erreurs fermées et historiques conservés |
| Écran Mes appareils et tableau de bord existants | Confirmation native, ON/OFF explicites via `/api/command`, état relu et disponibilité | UI FR/EN, aucun chemin réseau parallèle, aucun état ON/OFF optimiste dans la tuile |

La variante intégrée ouvre seulement une destination IPv4 privée explicite
(ou boucle locale pour tests), sans DNS, TLS ni découverte automatique sur Android.
Les profils externes à secret restent liés à leur hôte et, pour SET Meross,
**strictement limités à la boucle locale** avec approbation SHA-256 habituelle.
`profile check meross-mss310-manual` expose le SET et l’identité requise ; le
catalogue continue de décrire le profil canonique en lecture seule.

Linux peut réutiliser la même variante et le même moteur Rust avec `host`,
`port`, `discovery=false`, `vars.manual_uuid` et le secret `<instance>/key` du
coffre. Si `expected_uuid` existe déjà, il doit correspondre. La sélection unique
est une règle de l’adaptateur Android ; aucune procédure de déploiement Linux
n’est ajoutée ni qualifiée ici.

## Ce que confirme une commande

Chaque clic passe par le hub et sa garde habituelle. Le pilote vérifie l’origine,
l’UUID choisi et publié, le modèle MSS310, `System.All` et `System.Ability`, puis
émet au besoin un unique SET `Appliance.Control.ToggleX`, canal 0, valeur 0/1.
L’ACK est lié au message, au namespace, à l’UUID, à la version, à l’horodatage et
à la signature. Une **nouvelle lecture signée** doit confirmer l’état demandé
avant le succès API et le message « État confirmé ». Une demande déjà satisfaite
est vérifiée sans SET supplémentaire. Un ACK seul n’est jamais un état confirmé.

Le budget complet reste celui du hub (5 s), avec une réserve interne de 25 ms
pour clore les diagnostics. Après erreur/timeout d’envoi, aucune répétition,
reprise de file ou commande au redémarrage. L’état devient inconnu/hors ligne
jusqu’à une lecture d’identité valide. Un refus d’origine avant échange conserve
les mesures existantes. Les lectures ordinaires restent à leur cadence habituelle.
Les autres prises gardent uniquement leurs lectures.

Le rapport d’essai ajoute `MEROSS_IDENTITY` (première lecture d’identité,
y compris un refus de schéma au démarrage), `MEROSS_MANUAL_SELECTION`, `MEROSS_PREFLIGHT`,
`MEROSS_SEND`, `MEROSS_ACK`, `MEROSS_READBACK` et `UI_MEROSS_COMMAND` :
compteurs, dernières heures, durées, motifs fermés et interruptions. Les étapes
sont cumulées dans le moteur : le moniteur peut manquer un état transitoire sans
perdre son compteur d’erreur. Une tentative d’envoi n’est pas une preuve de réception ;
`MEROSS_SEND OK` nécessite le retour d’un ACK valide. `MEROSS_READBACK OK` prouve
la relecture de l’état, pas le fonctionnement d’un appareil alimenté par la prise.
Les UUID/IP, clés, noms, valeurs ON/OFF et payloads ne sont pas ajoutés à cet export.
L’UUID proposé pour une ancienne ligne sans association provient uniquement d’un
snapshot local authentifié, frais, en ligne, avec modèle MSS310 et identité unique ;
il reste en mémoire au plus 65 s et nécessite encore la confirmation dans l’écran.

## Essai sur S5e — réalisé par le propriétaire

1. Arrêter Moli et installer la bêta **par-dessus**, sans désinstaller ni effacer
   les données. Ne pas ressaisir les clés, relancer OAuth ou réappairer les prises.
2. Choisir une **prise non critique**, hors Finder/chaudière. Vérifier dans Meross
   que ses réglages et la limite de 30 minutes, si configurée, sont toujours présents.
   Moli ne lit, ne modifie, ne supprime ni ne recrée ces réglages.
3. Dans Mes appareils → MSS310, moteur arrêté, cocher « commandes manuelles »
   pour cette seule prise. Vérifier nom/adresse/UUID, confirmer, enregistrer,
   redémarrer. Si l’identité n’est pas encore connue : démarrer les lectures,
   attendre un relevé frais, ouvrir Mes appareils pendant que Moli tourne, puis
   arrêter et rouvrir l’écran. Aucun secret n’est à envoyer dans un message.
4. Sur l’accueil, vérifier la cible autorisée, puis **Allumer** une seule fois.
   Attendre « État confirmé par une relecture locale » et contrôler la prise.
   Faire **Éteindre**, attendre la même confirmation et vérifier physiquement OFF.
   En cas d’état non confirmé, ne pas multiplier les clics ; vérifier l’état dans
   Meross/sur la prise et arrêter l’essai. Moli n’effectue aucun renvoi automatique.
5. Attendre un passage du moniteur (environ 15 s), exporter le rapport d’essai
   habituel. Il doit distinguer sélection, précontrôle, envoi/ACK et relecture.
6. Pour revenir à la lecture seule : arrêter le moteur, décocher les commandes,
   enregistrer et redémarrer. **Arrêter Moli ou retirer l’autorisation n’éteint
   pas une prise déjà allumée** : vérifier OFF avant le retrait. Les autres prises,
   leurs clés, l’historique et Netatmo sont conservés.

Aucun essai réel n’est exécuté par Codex. Finder/chaudière exige un second accord
spécifique et un essai surveillé ; la bêta n’installe aucune régulation concurrente.

## Limites de qualification

Les signatures MD5 et HTTP sont celles du protocole Meross : pas de chiffrement
LAN, d’intégrité du payload ni de protection contre une substitution au milieu de
la transaction. Les origines sont celles des surfaces Moli existantes, pas une
preuve cryptographique indépendante d’un clic. La protection réseau/session et
la garde originales restent nécessaires. Le schéma ACK strict peut refuser un
firmware différent : un tel refus doit être diagnostiqué, jamais contourné.

Les tests utilisent exclusivement des clés inventées et des serveurs factices
(boucle locale Rust, interface propre d’un émulateur jetable Android). Ils ne
qualifient ni un firmware réel ni la précision des mesures. Les captures réelles
anonymisées et une éventuelle contribution amont restent distinctes.

Aucun namespace Timer, Countdown, routine ou calendrier n’est accessible par
cette voie : seuls les GET existants, GET Ability et SET ToggleX sont générés.
La limite Meross de 30 minutes est préservée, sans promesse de fonctionnement
hors Internet (documentation fournisseur contradictoire) ni de reprise sûre du
chauffage. Moli n’ajoute aucun minuteur compensatoire.
