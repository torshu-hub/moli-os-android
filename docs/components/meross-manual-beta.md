# MSS310 — bêta manuelle à activation explicite

Le profil canonique `meross-mss310` reste en lecture seule. Les installations
existantes et leurs quatre prises ne deviennent pas commandables à la mise à jour.
Chaque prise peut être autorisée indépendamment dans Mes appareils Android, après
confirmation locale du nom, de l’adresse et de l’UUID. Une prise déclarée critique
nécessite aussi un accord explicite sur son effet physique, lié au même UUID. Aucun compte cloud ou nouvel appairage.
Netatmo code29 (lecture, cadence 120 s, âge et renouvellement) reste inchangé ;
son endurance n’est pas requalifiée ici. Code37 ajoute une [règle simple
Netatmo → Meross](device-link.md), activée explicitement une fois par le propriétaire.
La variante et les permissions manuelles existantes sont conservées ; elles ne
suffisent pas à créer ou approuver une règle. Les anciennes démonstrations sont retirées.

## Écart minimal après la simulation 99f1a0c

| Mécanisme original | Extension et motif | Impact amont |
|---|---|---|
| Paquet MSS310 et profil déclaratif | Variante connue `meross-mss310-manual`, profil canonique + `manual-write.toml` | Même `meta.id`, catalogue, fixtures et codec ; aucun nouveau paquet, pilote natif ou format parallèle |
| Configuration Android + concordance + coffre Moli | `manual_control` absent/faux par défaut, consentement indépendant par prise ; `manual_uuid` distinct de l’association existante | Adaptateur Android uniquement ; ni clé ni `uuid`/`expected_uuid` existants remplacés |
| `hub.command`, garde, journal et échéance | `CommandRequest.origin` transmis au pilote ; variante manuelle limitée aux origines `Ui`/`Household`, sauf autorité supervisée ponctuelle code33 | Petit ajout générique au contrat runtime ; les autres pilotes gardent leur comportement |
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
coffre. Si `expected_uuid` existe déjà, il doit correspondre. La classification critique et sa confirmation
sont des métadonnées de l’adaptateur Android ; aucune procédure de déploiement Linux
n’est ajoutée ni qualifiée ici.

## Ce que confirme une commande

Chaque clic passe par le hub et sa garde habituelle. Le pilote vérifie l’origine,
l’UUID choisi et publié, le modèle MSS310, `System.All` et `System.Ability`, puis
émet au besoin un unique SET `Appliance.Control.ToggleX`, canal 0, valeur 0/1.
Le SET utilise `{"togglex":{"channel":0,"onoff":0}}` (ou 1), un objet et non
le tableau utilisé dans le digest de `System.All`. Référence de protocole :
[`MLToggleX` / `MEDictChannelMixin` au commit bafedb4](https://github.com/krahabb/meross_lan/blob/bafedb4f8563153f9e3494292073551bb28f5f9a/custom_components/meross_lan/helpers/entity.py#L373),
utilisé par [le switch ToggleX](https://github.com/krahabb/meross_lan/blob/bafedb4f8563153f9e3494292073551bb28f5f9a/custom_components/meross_lan/switch.py#L130).
La simulation initiale employait un tableau ; les deux serveurs factices vérifient
maintenant strictement le format objet attendu pour cette voie. Aucun essai
successif d’autres formats sur le matériel, ni reprise automatique, n’est ajouté.
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

## Multi-prises — code32

Les autorisations ne sont plus exclusives. Depuis **Mes appareils → Configurer**,
moteur arrêté, autoriser les prises souhaitées une par une, puis **Enregistrer**
et **Démarrer et afficher les données**. Une autorisation conservée depuis code31
reste valide ; les autres prises restent en lecture seule tant qu'elles ne sont
pas explicitement autorisées. Cocher une prise ne change jamais les autres.
Chaque fiche conserve ses propres Allumer/Éteindre, disponibilité et relecture.
Retirer une autorisation ne modifie pas l'état électrique ni les autres prises.

Si une prise alimente le chauffage, un relais Finder ou un autre usage critique,
cocher **Cette prise alimente le chauffage ou un équipement critique**. Modifier
cette classification retire l'autorisation manuelle de cette seule ligne jusqu'à
une nouvelle confirmation. L'habilitation nécessite ensuite deux validations :
identité nom/adresse/UUID, puis **Je comprends l'effet physique**. Annuler la
seconde laisse la prise sans commande. Le choix et l'accord lié à l'UUID sont
conservés ; une incohérence est refusée, jamais corrigée en accord implicite.
La fiche affiche **Chauffage / usage critique — commandes manuelles uniquement**.
La classification est déclarée par l'utilisateur ; Moli ne la devine pas d'un nom.
Si la projection Android manque, les réglages natifs restent accessibles et la
fiche signale que l'usage doit être vérifié dans Configurer.

Les étapes du rapport Meross agrègent désormais toutes les instances observées,
sans afficher leurs identifiants. Les compteurs restent cumulatifs : une relecture
réussie ne prouve pas un nouveau SET, ni un effet physique. L'arrivée d'une instance
ou une nouvelle lecture du même snapshot ne doit pas recompter les anciennes.

Qualification synthétique : 0/1/2/4 autorisations, conservation des associations
et permissions après sauvegarde/recréation/redémarrage et changement d'adresse
(réconciliation Rust), refus d'identité incohérente et d'accord critique manquant,
commandes réservées à la cible, refus des autres origines, absence de retry après
ACK inconnu. Les serveurs factices seuls reçoivent les essais de commandes.
Ne pas refaire le protocole réel ON/OFF acquis sur code30/31 pour qualifier ce lot.

L’[automatisation simple code37](device-link.md) réutilise intégralement ce
transport et ses diagnostics ; aucune approbation par événement n’est requise.

## Accès simplifié — code31

Le propriétaire a confirmé physiquement Allumer/Éteindre sur une prise de test
avec code30. Ce lot conserve ce chemin de commande et ne demande pas de refaire
cet essai. Le rapport analysé trace un SET confirmé ; plusieurs succès UI ne
prouvent pas plusieurs SET distincts.

- À l'ouverture Android, **Mes appareils** est visible sans passer par Paramètres
  ou Configuration experte. Moteur arrêté, il ouvre les réglages : choisir
  **Prises Meross**. Moteur en marche, il ouvre les fiches des appareils.
- Dans le tableau de bord, **Mes appareils** reste disponible dans l'en-tête natif
  et la navigation Maison. Les fiches montrent le nom, la disponibilité, l'état
  et la puissance récents. **Configurer** est présent sur chaque prise, même sans
  autorisation manuelle. Il ouvre directement les réglages Meross.
- Dans les réglages, le nom et les cases de lecture/autorisation apparaissent
  avant les **Détails de connexion** (adresse, clé, UUID). Arrêter le moteur pour
  modifier, confirmer chaque prise choisie, **Enregistrer**, puis **Démarrer
  et afficher les données**. Le démarrage revient à Mes appareils. Les clés
  vides conservent leur valeur chiffrée ; aucune association n'est recréée.
- En portrait, conserver la barre horizontale : **Système** et **Mes appareils**
  restent fixes ; la double flèche signale le défilement des autres destinations.
  Système garde sa cartographie originale et propose aussi Mes appareils.
- Même sans `home.config.android`, l'application Android conserve son bouton
  natif. Le tableau de bord détecte la plateforme par l'API Système existante
  et projette les MSS310 déjà publiées, avec leurs capacités réelles. Zéro appareil
  laisse un lien Configurer. Cela ne crée ni configuration ni autorisation.

Pour vérifier code31 : installer par-dessus code30, sans effacer les données,
puis retrouver les quatre noms et la prise déjà autorisée, les fiches et Configurer,
Système en portrait/paysage et le retour après rotation. Aucune nouvelle commande
ON/OFF ni nouvelle connexion Netatmo n'est nécessaire pour cet essai ergonomique.
La validation tablette de cette présentation reste à faire par le propriétaire.

## Essai matériel initial — procédure conservée

Cette procédure concernait code30, désormais validée sur une prise de test.
Elle reste une référence pour un futur choix volontaire d'une autre prise,
pas une demande de répétition.

1. Arrêter Moli et installer la bêta **par-dessus**, sans désinstaller ni effacer
   les données. Ne pas ressaisir les clés, relancer OAuth ou réappairer les prises.
2. Choisir une **prise non critique**, hors Finder/chaudière. Vérifier dans Meross
   que ses réglages et la limite de 30 minutes, si configurée, sont toujours présents.
   Moli ne lit, ne modifie, ne supprime ni ne recrée ces réglages.
3. Dans Mes appareils → Prises Meross, moteur arrêté, cocher « commandes manuelles »
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

Aucun essai réel n’est exécuté par Codex. Une habilitation manuelle d’usage critique exige la confirmation supplémentaire
décrite pour code32 ; les essais physiques restent ceux du propriétaire et
aucune autorisation d’automatisation Finder/chaudière n’en découle ; la bêta n’installe aucune régulation concurrente.

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
