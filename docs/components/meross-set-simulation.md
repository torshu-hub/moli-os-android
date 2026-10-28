# Meross SET signé — simulation uniquement

Ce document décrit le lot de simulation `99f1a0c`, validé séparément. Son
profil externe reste limité à la boucle locale. L’étape suivante, désormais
autorisée, est documentée dans [la bêta manuelle](meross-manual-beta.md) :
variante intégrée à activation explicite, sans automatisme ni commande par Codex.
Les garanties et preuves ci-dessous restent celles de la simulation.

## Écart minimal par rapport à Moli amont

| Mécanisme original réemployé | Extension | Motif et impact de synchronisation |
|---|---|---|
| `integrations/<id>/`, profil TOML, catalogue et confiance par SHA-256 | Variante synthétique dans `meross-mss310/simulation/`, hors `BUILTIN` | Aucun nouveau format de paquet ni nouvelle intégration active ; captures réelles anonymisées toujours à qualifier séparément |
| `moli-profile` et `[[write]]` | `transport = "meross"`, `namespace`, `channel` ; compilation stricte | Nécessaire car le GET des écritures ordinaires ne peut pas émettre SET/SETACK ; ajout isolé, GET existant inchangé |
| Codec MD5, transport HTTP borné et coffre existants | Même générateur de message avec méthode GET/SET, réponse liée à la requête | Pas de duplication du codec ou d’adaptateur Android ; petit module Rust portable `meross_set` |
| `Driver`/`DriverCtx`, `hub.command`, garde, échéance et journal | Branche de session pour contrôle avant SET et confirmation avant réponse au hub | Aucun changement de `moli-core`/`moli-runtime` ou du contrat de commande |
| `profile check/trust` | L’affichage indique SET, canal et restriction de simulation | Une approbation ne doit jamais présenter ce transport comme un GET ; mêmes contrôles de confiance et de liaison du secret à l’hôte |

Cette extension suit D2–D6 : déclaratif d’abord, briques dormantes, pas
d’installateur ou de catalogue parallèle. Le contrôle Netatmo de Coord 3 est
pris en compte : code29 est acquis pour ce lot ; endurance, documentation de
distribution et qualification de contribution amont restent distinctes.

## Surface déclarative et verrou de simulation

La variante `integrations/meross-mss310/simulation/profile.toml` n’est ni incluse
comme profil embarqué ni utilisée par Android. Ses données et celles du serveur
de test sont **entièrement synthétiques**. Elle utilise les outils originaux :

```toml
[[write]]
point = "on"
path = "/config"
transport = "meross"
namespace = "Appliance.Control.ToggleX"
channel = 0
refresh = "all"
```

La compilation exige un hôte **littéral de boucle locale** (`127.0.0.1` ou `::1`),
HTTP, un secret déclaré, un point binaire sans conversion qui lit directement
`all.payload.all.digest.togglex.[channel=0].onoff`, une identité UUID issue de
la même lecture `Appliance.System.All` et un `refresh` signé JSON `/config`.
Pas de payload, template de valeur, `map`, `decimals`, `expect` ni namespace
arbitraire pour ces écritures. La configuration doit fournir `expected_uuid`.
Le profil fichier conserve son hôte fixe et son approbation exacte : un secret
sans hôte ou une redirection de l’instance vers un autre hôte reste refusé.

Le contrôle de boucle locale est répété **avant tout échange de cette voie**.
Il n’y a ni résolution de `localhost`, ni recherche LAN, ni suivi automatique
d’adresse pour le profil externe. Ce lot ne peut donc pas être utilisé pour
commander une prise physique en changeant simplement l’hôte dans sa configuration.
Retirer cette restriction constituerait un nouveau lot et une autorisation réelle.

## Une commande dans le pilote original

1. Le hub valide droits, type, garde, cible et échéance (5 s). Chaque instance
   traite ses commandes en série ; les commandes abandonnées ne sont pas rejouées.
2. Le pilote relit `System.All` et compare l’UUID aux identités configurée et
   publiée. Un canal 0 unique avec un état entier 0/1 est requis.
3. Il lit `System.Ability` et exige que `Control.ToggleX` soit annoncé.
   Une commande déjà satisfaite est confirmée par cette lecture sans nouveau SET.
4. Avant un SET, l’ancien état du point passe à `null`. Le payload est généré
   dans Rust : un seul canal 0, `onoff` entier 0/1. Nouvelle vérification de
   l’abandon avant envoi ; aucun nouvel échange après l’échéance.
5. Un HTTP 200 ne suffit pas : méthode ACK, namespace, identifiant de message,
   origine UUID, version, horodatage et signature doivent correspondre ; un
   `SETACK` attend un payload objet vide et sans erreur.
6. Un nouveau `System.All` signé doit confirmer le même UUID et la valeur demandée.
   Alors seulement sont publiés l’état relu et le succès au hub. Échec, discordance
   ou timeout : état inconnu/hors ligne et résultat d’erreur au journal existant.

Les transitions internes sont fermées et bornées par commande : précontrôle,
tentative d’envoi, acquittement, confirmation, refus ou inconnu. Les messages
d’erreur FR/EN distinguent refus avant envoi, issue d’envoi inconnue et ACK reçu
sans confirmation. Les traces nouvelles ne copient pas de clé, UUID, adresse,
payload ou erreur réseau brute. Le journal privé du hub conserve sa cible selon
son contrat d’origine. Aucune nouvelle surface de diagnostic Android n’est créée.

Une tentative d’envoi ne prouve pas que l’appareil l’a reçue. Après un timeout,
**aucun renvoi automatique**, même après redémarrage : seules les lectures
reprennent. Une nouvelle commande explicite refait le précontrôle ; si l’état
voulu a déjà été appliqué, aucun SET supplémentaire n’est envoyé.

## Preuves et limites

Les tests utilisent un vrai serveur HTTP **factice sur boucle locale**, une clé
inventée dans un coffre temporaire, un fichier approuvé par le mécanisme Moli
et le vrai `ProfileDriver`/hub. Ils couvrent ON/OFF, répétition et redémarrage,
substitution d’identité, mauvaise clé/ACK/signature, HTTP 200 trompeur, capacité
absente, relecture absente/en échec/discordante, timeout après envoi ou ACK,
abandon avant SET, expiration, garde, secret absent, profil non approuvé ou sans
droit d’écriture, et changement explicite du port du simulateur à UUID stable.
Le changement d’hôte d’un profil à secret reste bloqué ; ce n’est pas une
qualification du suivi d’IP sur un LAN réel.

La signature MD5 est celle du protocole Meross, **pas une garantie cryptographique
d’intégrité du payload ou de protection contre un attaquant du LAN**. Elle ne
remplace ni TLS ni une liaison atomique entre précontrôle et envoi. L’UUID et
la capacité relus réduisent les erreurs de cible connues ; aucune garantie
contre une substitution au milieu de la transaction n’est revendiquée.

Le schéma ACK/origine/fraîcheur et le canal sont ici qualifiés sur données
synthétiques uniquement. Modèle, firmware et captures réelles anonymisées restent
à qualifier avec l’utilisateur avant un partage présenté comme validé matériellement.
Pas de contribution, issue ou PR vers l’amont autorisée par cette simulation.

Vérification ciblée : `cargo test -p moli-profile meross_set --locked`, puis
contrôles génériques requis par le changement de profil (`scripts/check.sh`).
Le lot de simulation seul ne produisait pas d’APK. La livraison manuelle suivante
est décrite dans [meross-manual-beta.md](meross-manual-beta.md).
