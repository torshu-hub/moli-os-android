# Code24 — architecture ciblée après code23

Le moteur Moli, Hub, pilotes, API et configuration unique restent en place.
Cette évolution du fork ne change ni le protocole OAuth, ni les commandes, ni les
formats des comptes chiffrés. Android conserve le cycle de vie du serveur, le
verrou JNI, les transactions de `ConfigStore`, Keystore, permissions et navigateur.

## Points Hyundai

Les tables protocole `status-fields.json` et `aliases.json` déclarent maintenant
le type de chaque point. Une valeur manquante reste `null`, avec le même type de
point que la valeur connue. Les scalaires numériques textuels sont convertis ;
les valeurs structurées non prévues restent inconnues. Les alias des mesures
canoniques (SOC, SOH, température, odomètre, puissance, énergie, autonomie,
pressions) reprennent leur valeur normalisée. SOH 0/255 reste inconnu partout ;
SOC 0 reste une valeur valide. Les identifiants et magnitudes existants restent
inchangés. Les pressions conservent l'unité explicitement rapportée par le
fournisseur et leur point compagnon `_unit` ; une unité absente ne vaut pas bar.
Les champs protocole bruts restent distincts des mesures converties.

`vehicle_at` date l'acquisition physique rapportée ; `cloud_at` est la date du
cache lorsqu'elle est fournie ; `received_at` date la réception locale de ce
cache. La date d'un Sample Hub est sa publication, pas une nouvelle mesure du
véhicule. Chaque résultat auxiliaire possède son propre `received_at`, y compris
un échec ; réutiliser un résultat auxiliaire ne rajeunit pas sa date. La qualité
et le résumé `snapshot` expirent entre deux polls, sans appel fournisseur ni
modification des dates sources. Les gardes des commandes vérifient toujours la
date physique au moment de l'action. Les appels réseau restent bornés ; une
expiration échue pendant un appel est appliquée à son retour avant publication.

Le point diagnostique comparatif `read_capabilities`, les deux inventaires de
comparaison HA et leur écran sont retirés. Les capacités réelles de commandes,
les champs protocole, les licences et attributions restent. Aucun nouveau champ
ou équipement n'est déduit d'une comparaison externe.

## Setup réutilisable et opérateur hors APK

`moli-setup` expose `hyundai::run`, `netatmo::run`, `meross::run` et les opérations
`configuration`, extraites des fonctions existantes. Ce crate compile sur Linux
sans JNI/Android. L'hôte sérialise les opérations, arrête le moteur avant les
mutations et protège les entrées/sorties privées. Les fonctions retournent les
candidats TOML ; elles ne remplacent pas le fichier de configuration.

Compiler uniquement l'opérateur :

```
cargo build --locked -p moli-setup --bin moli-setup
```

Le gestionnaire de secrets de l'installation doit fournir **la clé maître
existante** dans `MOLI_MASTER_KEY`, et `MOLI_DATA_DIR` désigne son répertoire de
données existant contenant `moli.toml`/`secrets.enc`. Ne pas copier de clé dans
une commande, un script, le TOML ou une transcription. Arrêter le service Moli,
exclure tout autre administrateur et ouvrir une seule session opérateur :

```
target/debug/moli-setup --engine-stopped
```

Le drapeau confirme une condition vérifiée par l'opérateur ; ce binaire ne peut
pas arrêter ni verrouiller tous les hébergeurs externes. Aucun listener réseau
n'est créé. Le flux stdin privé reçoit un objet JSON par ligne (64 KiB maximum),
stdout retourne un objet résultat ou `{"error":"code_fermé"}`. Ne pas enregistrer
la session : URL d'autorisation, callback et candidats de configuration sont des
sorties privées. Un tube depuis un outil opérateur évite l'historique du shell.
Les champs `master` et `data` sont imposés par l'environnement, pas par la requête.
Exemple sans secret : `{"action":"hyundai","operation":"status"}`.

| Fournisseur | Parcours existant exposé par `action` et `operation` |
|---|---|
| Hyundai | `status`; `begin_login` avec `api_mode` (`europe`/`gspa`), PIN facultatif et `consent:true`; `complete_login` avec callback exact ; `discover` pour reprise de découverte ; `configure` avec `options`; `cancel_login`; `disconnect`. Alternative `login` avec identifiants fournis par le canal privé. |
| Netatmo | `status`, `provision`, `begin`, `complete`, `check`, `homes`, `prepare_home`, `cancel`, `disconnect` ; application personnelle et sélection d'instance comme dans Android. |
| Meross | `status`, `signin`, `inventory`, `identify`, `prepare`, `prepare_found`, `forget` ; compte cloud et configuration des prises restent distincts. |

Les champs exacts et codes sont ceux des fonctions publiques, inchangés depuis
leur extraction ; les opérations réseau et `identify` ne sont pas des tests
implicites. Pour les candidats TOML, l'hôte compare la configuration initiale,
valide avec `moli-os check-config`, sauvegarde les données utiles puis effectue un
remplacement atomique avant de redémarrer le service. L'opérateur minimal ne
fait aucune installation automatique de candidat.

L'interaction navigateur demeure spécifique : ouvrir la page fournisseur dans
un navigateur privé et restituer le callback HTTPS **exact** au même compte.
Ne pas substituer localhost, changer le redirect URI ni désactiver state/TLS.
Hyundai persiste le ticket chiffré et l'autorisation en attente de découverte ;
un callback invalide n'est jamais échangé, le ticket valide est consommé avant
l'échange ambigu. Netatmo conserve son début OAuth en mémoire : garder le même
processus entre `begin` et `complete`; après interruption refaire `begin`, tout
en conservant le compte déjà enregistré. Ce sont les limites existantes, pas
une nouvelle authentification universelle.

## Carnet et associations

`moli-history::journal::Journal` reçoit les journées fournisseur et chaque
observation physique datée. Il compose explicitement météo/compteur à partir des
points configurés, de leur unité et fraîcheur. Le pilote ne possède plus le
Store ni les conversions de cette composition. Un problème SQLite émet un
diagnostic fermé et peut être retenté à la collecte suivante ; il ne transforme
pas les lectures Hyundai en panne fournisseur. Une écriture échouée n'est pas
une observation durable, aucun rattrapage complet n'est revendiqué.

Le carnet ne dépend pas des événements Hub, qui peuvent supprimer les valeurs
inchangées. Une nouvelle date physique avec les mêmes valeurs est transmise ;
les doublons de date sont gérés par le Store existant. Les schémas, clés, tables,
finances, étiquettes et options restent inchangés. Il n'y a aucune migration de
base, nouvelle politique de rétention, archive ou import d'historique.

Avec `history_days=0` et une instance Hyundai configurée, le carnet reste
accessible via l'API existante, sans démarrer recorder ni purge générale. Les
anciens échantillons déjà stockés restent lisibles ; aucune nouvelle collecte
historique générale ne s'y ajoute. Sans Hyundai, le comportement historique
reste celui de code23. Une base illisible au démarrage n'empêche pas le moteur
de démarrer, mais son API historique attendra un redémarrage après réparation ;
la façade carnet tente indépendamment de rouvrir son Store à la collecte.

## UI, compatibilité et qualification

Android conserve sélection des véhicules et consentements visibles ; cadence
et âge maximal sont dans les options avancées, météo/compteur dans les
associations du carnet. Les groupes fermés conservent leurs valeurs, y compris
les références temporairement indisponibles. Le dashboard sépare lectures et
détails techniques sans changer ses actions volontaires.

Mise à jour code23→24 avec même identité/certificat, configurations et coffres.
Pas de migration de données. Le retrait du seul point comparatif peut toucher
un consommateur qui le lisait explicitement ; il ne faut pas le remplacer par
une capacité inventée. Le typage explicite corrige les métadonnées des points
au premier échantillon, sans changer leurs identifiants. Un retour Git ne
restaure jamais les données d'exécution ; Android ne garantit pas le downgrade
vers un versionCode inférieur sans procédure de sauvegarde adaptée.

Évolutions officielles examinées jusqu'à `1712827f8aea9fd303941119a94882e856bcbaeb` :
`Semantic::Position` concerne les volets ; notices runtime liées au serveur
amont hors périmètre. Pas de fusion globale. Adaptations partagées du fork :
façade `moli-history`, disponibilité du carnet dans `moli-os`, setup partagé,
carte Web et traductions. Aucun nouveau bilan ni changement de l'API de finance.

Les tests utilisent des comptes/coffres et véhicules fictifs. Ils vérifient
conservation et sécurité logicielle, pas les lectures exactes d'une Ioniq, le
renouvellement prolongé ni les effets d'une commande physique. Ces qualifications
restent volontaires sur appareil réel après livraison de l'APK signée.
