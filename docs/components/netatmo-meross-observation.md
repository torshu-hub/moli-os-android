# Netatmo → Meross : observation continue à blanc et essai supervisé — code35

Code32 reste acquis : quatre permissions manuelles indépendantes, identité,
SET signé, ACK et relecture. Code33 relie les données du pilote Netatmo existant
au hub, au moteur d'automatismes original et à une cible choisie. Aucun second
thermostat, calcul température/consigne, transport fournisseur ou scheduler ajouté.

Dans **Mes appareils**, choisir le thermostat NATherm1/OTM qui publie la demande et
la MSS310 cible, puis enregistrer. Noms et modèles sont visibles ; instance et
identifiant sont dans les détails. La demande cloud, l'intention simulée, l'état
réel Meross, la date de collecte et un motif de refus restent distincts.
L’aperçu instantané se rafraîchit tant que cet écran est visible. Code35 ajoute,
après préparation du scénario quotidien, un démarrage/arrêt explicite de
l’**observation continue à blanc**, qui suit les déclencheurs natifs même après
sortie de cet écran. Son journal compact et son compteur restent distincts des
lectures et des commandes. Elle n’active ni n’approuve le scénario et ne commande
rien ; voir le contrat code35 en fin de document.

Le pilote traduit uniquement `boiler_status` booléen officiel de ces deux modèles :
`true → declared_demand="on"`, `false → "off"`, champ absent → `"missing"`,
invalidation → `"unknown"`. Un champ mal typé est refusé par le parsing existant.
`heating_power_request`, températures et consignes ne déterminent jamais un ON.
Cela décrit une demande déclarée via le cloud, pas une flamme ni une sécurité.
La cadence Netatmo validée de code29 n'est pas modifiée ni accélérée par cet écran.

## Architecture originale et périmètre

| Élément | Réutilisation / ajout limité |
|---|---|
| Pilotes Netatmo et profil MSS310 | Lectures existantes, variante manuelle, identité, SET/ACK/readback inchangés |
| Hub et snapshots | Une admission cohérente avant/après le calcul ; origine et journal conservés |
| `moli-automation` | Graphe code32 réutilisé ; aperçu éphémère via `run_now(dry=true)` ; essai séparé via le même exécuteur |
| Approbation | Empreinte originale SHA-256 et session humaine originales ; approbation supervisée sans activer de scheduler |
| Runtime | `CommandPermit` générique non sérialisable, révocable, borné à un point/une valeur et une admission vivante |
| API Rust | Petit adaptateur source/cible, sélection et parcours d'essai ; portable Android/Linux |
| Android | Écran natif de consentement ; connexion humaine privée au seul loopback authentifié |

Le brouillon d'aperçu `!preview-…` n'entre ni dans la liste, ni dans les fichiers,
ni dans l'historique des runs. Il ne peut être approuvé, activé ou exécuté pour de
vrai. Le graphe d'essai réel est au contraire un scénario original, visible,
approuvé sur sa version exacte, **toujours désactivé**. Il n'a qu'un déclencheur
manuel, des conditions et un seul Set. Aucun déclencheur périodique, de reprise
ou de changement de demande ne peut être introduit dans ce parcours.

## Contrat d'admission

L'observation exige source et cible en ligne, pilotes en marche, vraie publication
Netatmo depuis le démarrage, `declared_demand` textuel strict, `freshness=cloud_recent`,
collecte positive non future et âge inférieur au `max_age_seconds` publié par le
pilote (1..3600 s). Les échantillons utiles doivent appartenir à une publication
terminée. L'identité cible MSS310, son UUID et l'instance doivent correspondre ;
`reported_model` doit dater de moins de 65 s. Les révisions, dates et données sont
contrôlées avant/après le dry-run. Première observation après reprise/changement :
aucune intention ; la lecture suivante évalue les données courantes, sans rattrapage.

Demandes inconnues, absentes, nulles, booléennes au mauvais point, dates invalides,
stale, déconnexion, publication partielle ou changement pendant le calcul ne
produisent aucune intention applicable. Les répétitions identiques réutilisent
le résultat de l'exécuteur, avec nouvelle admission, sans saturer son historique.
L'interface masque immédiatement un résultat expiré, masqué ou incohérent avec
les valeurs reçues. Un renouvellement de timestamp à valeur identique n'est pas
présenté comme un changement de demande.

Pour un **essai réel**, le `boiler_status` booléen frais doit aussi correspondre
exactement à la demande textuelle. La cible doit déjà disposer de la variante
manuelle habilitée et du même UUID. Cette permission manuelle ne vaut jamais
permission d'automatisation.

## Essai réel volontaire — une fois, en présence du propriétaire

1. Installer la bêta par-dessus code32, sans désinstaller ni effacer. OAuth, maison,
   coffre, clés et quatre prises sont conservés. Aucune commande n'est nécessaire
   pour vérifier simplement l'observation.
2. Choisir source/cible et comparer la demande affichée à Netatmo. Lire l'état réel
   Meross. Un essai sur lampe reste facultatif : le protocole manuel déjà acquis
   n'a pas à être requalifié avant chaque livraison.
3. Avant tout essai chauffage, faire confirmer l'état du vase d'expansion et la
   pression/condition de chaudière, l'isolement/boîtier/câblage Finder 230 V, la
   compatibilité de l'entrée thermostat, et le comportement du relais/commande
   en perte de Wi-Fi, tablette ou cloud. Ne pas cocher un prérequis inconnu.
   Aucune mesure ou manipulation sur des bornes 230 V n'est demandée par Moli.
4. **Préparer un essai réel volontaire** ouvre une page distincte. Les cinq
   confirmations (dont présence et arrêt manuel sûr) sont décochées au départ,
   non sauvegardées, révoquées en quittant ou recréant la page. Après confirmation,
   préparer un ordre unique ; vérifier source, cible, demande et version exacte,
   puis approuver et appliquer **une fois** dans la confirmation finale.
5. La préparation expire en 60 s au plus, et plus tôt si les données expirent.
   Si la source/identité/version change entre-temps, l'essai est refusé. Il n'est
   jamais rejoué automatiquement. Le succès exige l'ACK et la relecture Meross,
   pas seulement un envoi. Vérifier soi-même l'effet matériel et l'état final ;
   exporter le rapport habituel. Un résultat ambigu impose une vérification,
   pas des clics répétés. Avant de cesser la surveillance, éteindre volontairement
   la prise choisie depuis sa fiche et vérifier OFF ; en cas de doute, utiliser
   l’arrêt manuel sûr.

Android ouvre une session humaine originale seulement après le dernier clic
natif. Le PIN déjà protégé par Keystore reste dans le processus natif ; aucun
cookie humain n'est déposé dans la WebView. La session est fermée après l'essai,
y compris en erreur. Si le code du tableau de bord a été changé, un champ natif
facultatif permet de l'utiliser sans modifier les identifiants existants. La page
est non exportée et protégée contre les captures. Linux réutilise API, moteur,
profil et session PIN ; aucun nouveau service d'hébergement n'est nécessaire.

L'autorité physique expire après 6 s au plus, vise **un point et une valeur**,
revérifie source, sélection et empreinte approuvée jusqu'à la frontière SET,
et suit les jetons d'arrêt du moteur. Elle n'est présente que dans ce run.
`Origin::Automation` sans cette autorité reste refusé par la variante Meross ;
les autres prises manuellement habilitées n'en obtiennent aucune. La garde du
hub, ses délais et son journal restent dans le trajet. Annuler/révoquer ne met
aucun ordre en attente et ne crée pas d'OFF implicite.

## Pannes et limite d'usage

| Situation | Comportement logiciel / limite physique |
|---|---|
| Netatmo inconnu, ancien, incohérent ou hors ligne | Aucun ON ni essai depuis cette demande ; pas de thermostat de remplacement |
| Sélection, version, consentement ou session invalides | Refus avant exécution ; pas de droit hérité des permissions manuelles |
| Révocation/arrêt pendant le précontrôle | Admission invalide, aucun nouveau SET ; un SET déjà transmis ne peut pas être rappelé |
| Wi-Fi/Internet/tablette/Moli coupé après ON | **Aucun OFF garanti** ; la prise peut rester ON. Vérifier l'état et employer l'arrêt manuel sûr |
| ACK/readback absent | État non confirmé, aucune reprise automatique |
| Redémarrage, mise à jour ou restauration | Association conservée ; aucune autorité physique restaurée, aucun rattrapage |

La limite Meross de 30 min n'est ni modifiée ni traitée comme une protection
indépendante qualifiée. Moli n'accède à aucun Timer/Countdown. Les propriétés
hors réseau du montage et les protections indépendantes de la chaudière doivent
être qualifiées avant tout usage autonome sans surveillance.

**La régulation continue n'est pas activable dans code33.** Le code livré est un
trajet réel ponctuel, humain, borné et révocable, pas une boucle de suivi laissée
en service. La qualification matérielle et une politique d'exploitation sûre
avec protections indépendantes manquent encore ; cette bêta ne prétend pas les
remplacer par une case ou un minuteur logiciel. Elle permet d'observer les vraies
données puis, seulement si les prérequis sont confirmés, de vérifier volontairement
un trajet complet Netatmo → moteur → prise → contact Finder.

## Stockage, diagnostics et vérification

`netatmo-meross-observation.json` versionné, mode 0600, remplaçable atomiquement,
contient seulement révision, source/cible et noms. Une corruption bloque
l'enregistrement plutôt que d'écraser le fichier. Il n'ajoute aucun droit au
coffre, à `moli.toml`, aux associations ou permissions Meross. Un essai préparé
crée/réutilise son propre scénario original désactivé ; il reste visible dans
l'Atelier mais ne reçoit aucune autorité persistante. Les consentements et
préparations sont uniquement en mémoire ; le journal original conserve l'origine.

Le rapport existant ajoute `UI_OBSERVATION` (motif fermé, transitions regroupées)
et `UI_HEATING_TRIAL` (préparé, en cours, relecture confirmée, non confirmé, révoqué).
Les étapes Meross preflight/send/ACK/readback existantes restent la preuve du
trajet. Aucun nom, UUID, IP, demande ON/OFF, PIN, cookie, jeton ou payload n'est
ajouté à l'export. « Relecture confirmée » ne prouve pas la chauffe d'une chaudière.

Tests ciblés : admission source/cible, persistance, reprise, données invalides et
cohérence, zéro commande en observation ; approbation humaine exacte et cinq
prérequis, mauvaise prise/valeur, révocation pendant préflight, SET signé et
readback sur faux serveur, absence de rejeu/autorité au redémarrage, session HTTP
originale et logout, navigateur FR/EN portrait/paysage, page native bloquée et
consentements non restaurés, préservation 0/1/4 permissions. Les fixtures de
WebView sont réservées à l'APK de tests. Tous les essais de Codex sont synthétiques.
La réception réelle sur S5e et l'état matériel restent à confirmer par le propriétaire.

## Daily native candidate — locked delivery

The daily preparation button creates/reuses one **disabled** `Mode::Single`
scenario in the existing automation store. It uses `every(minutes=1)` plus
`when_state(collected_at)`; no additional timer, thermal calculation or provider
request is introduced. Both triggers traverse native `if`/`stale` conditions,
check that declared demand agrees with the boolean boiler field, and propose a
SET only when a fresh boolean plug readback differs. A missing/unknown readback
never qualifies as OFF. There is no startup trigger, queue, catch-up or retry
node. Preparing again preserves the scenario ID and fingerprint at unchanged
selection/freshness contract; restart recovers the draft without arming it.

A volatile, read-only admission predicate is bound to the exact graph fingerprint,
source/target and selection revision. At the original executor's SET boundary it
rechecks the existing strict admission: driver and device availability, source
publication, collection timestamp, field coherence, model and readback freshness.
Graph edits, selection changes, lost source/target, restart or expired samples
refuse the intention. No command permit is created by this predicate. Removing
the marker in an edited generic graph cannot confer Meross automation authority.

**This candidate is not an activable autonomous heating controller.** A reserved
node `moli_daily_heating_locked_v1` identifies the release interlock: generic
human approval is refused, restored enable/approval flags are cleared before the
native scheduler starts, and real SET execution is refused even if an internal
caller forges an enabled graph. The one-shot code33 authorization remains separate.
Manual ON/OFF, signed ACK/readback and the original guards are unchanged. Future
autonomous authority needs a separate qualified release and explicit local
version/target/physical authorization; neither installing this APK nor preparing
the draft can supply it. In particular, future network-error/manual-override
handling must not be implemented by simply deleting this interlock.

The daily state stays **disabled**, with separate readiness **ready/refused**.
There is no "in service" claim or activation button in this release. Readiness
means the exact draft and current data can be examined, not physical validation.
`GET /api/observations/netatmo-meross/daily` reads this state; `POST` with the
current observation revision/epoch prepares the disabled candidate. Household
access, bounded bodies and the original Android cookie protect these routes.
Existing MCP `list_automations`, `get_automation` and `test_automation` can inspect
and dry-test the candidate in the local authenticated instance. No new MCP server,
ADB bridge, cloud task or externally exposed tablet port is added.

Loss policy for this delivery is deterministic: **no automatic SET at all**,
including OFF, restart and restoration. An already-ON plug can remain ON after
network/service/tablet loss; its physical state is uncertain until fresh readback.
The UI preserves manual OFF and states this limitation. The original trial report
adds only `UI_DAILY_HEATING` readiness/transport categories, coalesced using the
existing logger, without identifiers or sample values. No hardware claim follows
from a prepared graph or a successful synthetic test.

## Code35 — explicit continuous dry observation

After choosing the source and target in **My devices**, prepare the daily draft,
then choose **Start continuous dry observation**. The graph stays disabled and
unapproved. The only new registration is volatile and **dry-only**, inside the
existing `moli-automation` clock/event engine; no additional timer, subscription,
network poll, provider connection or command permit is created. The exact native
`every` or `when_state(collected_at)` trigger traverses the original graph and
executor with `dry=true`. Generic approval, restored enable flags and real SET
remain independently locked. Code33's supervised one-shot path remains separate.

The current version/fingerprint, source/target selection, publication validity,
coherent declared demand, driver/device availability and fresh typed Meross
readback are checked. A newer failed/incomplete Netatmo collection refuses a
current intention. Conditions and SET admission are checked again by the native
executor, and a coherent witness before/after the evaluation is required. The
status reports simulated ON/OFF, already matching readback, or a closed refusal
reason; it never reports physical execution. A publication that finishes after
its state event can be refused as pending, then evaluated by the next ordinary
minute deadline; there is no retry loop or extra provider request.

Starting skips the current publication. Loss of valid data clears that baseline;
the first valid publication after recovery only establishes a new baseline.
There is no startup replay or missed-deadline catch-up. An engine scheduling gap
longer than five seconds cancels the previous evaluation and marks the resumed
clock minute as consumed. A command on the selected target also discards the old
intention; subsequent readbacks and publications are evaluated afresh. This does
not undo, repeat or grant permission for that command. Leaving the screen keeps
observation running; **Stop observation**, changing the saved selection, editing
its graph, or stopping the engine cancels it. Restart/restoration never restores
an observation registration or any command authority.

A maximum of 32 in-memory decision transitions is retained, identical decisions
are coalesced, omitted transitions are counted, and trigger counts distinguish
publication events from periodic deadlines. Native observation runs stream through
the existing local automation events but do not fill the persisted run history.
The existing Android trial report receives only closed numeric status/reason and
bounded event counts under `UI_DAILY_HEATING`; no names, IDs, samples, fingerprints,
URLs, tokens or secrets are added to that export. Loss of local UI transport or
expired data hides the last current intention; historical decisions stay labelled
as simulations. An already-ON plug may remain ON after STOP/network loss; manual
OFF with verified readback remains available through the existing controls.

`GET /api/observations/netatmo-meross/daily` includes this volatile status.
`POST …/daily/observation` accepts only revision, epoch, exact fingerprint and
`action: "start" | "stop"`. Start requires the current prepared version; stop is
also accepted from an outdated view so a changed selection cannot trap observation
in an active state. Household/visitor restrictions, body limits and Android's
private loopback cookie are unchanged. There is no new MCP server or tablet bridge.

All development tests use synthetic drivers/local HTTP/browser/WebView fixtures.
Neither physical reception on S5e nor autonomous heating operation is established
by this release. Install over the existing beta without clearing its data; later,
the owner can compare the displayed simulation with Netatmo and Meross without
issuing any command.
