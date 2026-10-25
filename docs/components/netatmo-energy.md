# Netatmo Energy : premier incrément en lecture seule

`moli-netatmo` est un pilote Rust du hub d'origine, partagé par le serveur embarqué
Android et le serveur Linux. Aucun transport Android, appel JNI métier, thermostat
logiciel, commande Meross ou commande de chauffage n'est ajouté. Il ne démarre
aucune tâche sans instance activée. Aucun compte réel n'a été connecté pour cet incrément.

## Contrat vérifié dans la documentation publique (25 octobre 2026)

- [Energy](https://dev.netatmo.com/apidocumentation/energy) : `GET /api/homesdata`
  fournit la topologie, les identités, le mode maison et les programmes ;
  `GET /api/homestatus?home_id=…` fournit les états. Scope : `read_thermostat`.
- `rooms[].therm_measured_temperature`, `therm_setpoint_temperature` et
  `therm_setpoint_mode` sont les température, consigne et mode déclarés. Le
  programme sélectionné est lu dans `homesdata.schedules[].selected/name` ;
  la grille hebdomadaire complète n'est pas publiée dans les points de cet incrément.
- `modules[].boiler_status` est documenté comme booléen pour **NATherm1 et OTM**.
  Moli le présente comme un **état/demande déclaré par Netatmo**, pas une mesure de
  flamme ni une preuve de contact électrique fermé. Sa disponibilité et sa
  signification sur l'installation réelle restent à vérifier avec le propriétaire.
- `rooms[].heating_power_request` est un **pourcentage demandé par les vannes**.
  Aucun seuil ne le convertit en ON/OFF. NRV, OTH et les produits inconnus ne
  reçoivent pas un état binaire inventé. OTH peut fournir la passerelle d'un OTM ;
  seul le champ booléen documenté de l'OTM est consommé.
- `time_server` date la réponse cloud. Ces schémas Energy ne fournissent pas de
  timestamp de mesure physique pour ces valeurs. `measurement_at` reste donc
  **null**. `reachable=true` + réponse récente n'est pas une preuve de fraîcheur
  de la mesure physique. L'interface affiche cette limite en permanence.
- [OAuth](https://dev.netatmo.com/apidocumentation/oauth) : secret client exclusivement côté serveur (interdit dans une application mobile), code d'autorisation
  officiel, puis refresh token. La documentation demande de remplacer **les deux**
  jetons après renouvellement ; les anciens sont invalidés. Moli les conserve
  ensemble dans un unique secret chiffré avant toute lecture avec le nouveau jeton.
- [Quotas](https://dev.netatmo.com/guideline) : 50 requêtes / 10 s et
  500 / heure / utilisateur ; quota d'application distinct (2000 / heure pour
  moins de 500 utilisateurs). Deux lectures toutes les 120 s représentent environ
  **60 / heure par instance**, plus les renouvellements OAuth. Partager ce budget
  avec les autres applications/instances du compte ; aucune requête n'est faite
  pour mesurer le quota.

Les pages officielles sont rendues côté navigateur. Leurs ressources publiques
`POST /api/getdocumentationjson` (`fileName=Energy`) et
`POST /api/getwysiwyglayoutjson` (fichiers `connect/apidocumentation/oauth/file.json`
et `connect/guideline/file.json`, champ `container` vide) ont permis de lire les
schémas et textes actuels, sans authentification ni appel au compte Energy.

Comparaison ciblée : [Brizgir/moli-os](https://github.com/Brizgir/moli-os/tree/d18bf65fa1f3f27132a0acee767ece72079190eb),
Apache-2.0, conserve les conventions Driver/hub/coffre/catalogue de Moli ; son arbre
public ne contient pas de pilote Netatmo à cette révision. Pas de code tiers ou de
capture de compte importé. Les fixtures de ce paquet sont entièrement synthétiques.
Le profil déclaratif existant ne gère pas la rotation OAuth atomique et les
invalidations nécessaires : ce chemin est donc un pilote natif.

## Configuration portable, sans migration implicite

Ajouter une instance à la configuration Moli existante, sans remplacer le fichier :

```toml
[[driver]]
id = "energy"                 # stable ; ne pas renommer après association
kind = "netatmo"
enabled = false                # activer seulement après consentement et provisioning
[driver.options]
home_id = "home-test"          # exemple fictif, remplacer localement
client_id = "app-test"         # identifiant de l'application officielle autorisée
poll_seconds = 120             # plage 120..3600
max_age_seconds = 300          # entre poll_seconds et 3600
```

Le nom d'instance est aussi l'espace de coffre. Secrets **hors config** :

- `energy/oauth_tokens` : un seul objet JSON
  `{"access_token":"…","refresh_token":"…","expires_at":1800000000}` ;
  `expires_at` est en secondes Unix, contrairement aux points d'horodatage (ms).
- `energy/client_secret` : secret de l'application OAuth, uniquement dans le coffre.

Sur un serveur confidentiel Linux avec CLI (jamais dans l'APK), le propriétaire fournit ces valeurs par un tube à
`moli-os secrets set energy oauth_tokens` et `moli-os secrets set energy client_secret`,
sans arguments secrets, historique shell, sortie console ni fichier en clair.
Réutiliser la clé maître existante. Le pilote ne crée ni compte ni application.
Le `client_id` et le secret sont nécessaires au renouvellement ; sans eux une
session initiale valide peut lire jusqu'à son expiration puis s'arrête explicitement.
Un token de renouvellement dont la réponse fournit un scope autre que le seul
`read_thermostat` est refusé. Une réponse OAuth sans scope est acceptée selon le
contrat officiel : le consentement initial doit donc bien être limité à la lecture.

Android embarque le même pilote et le même coffre, avec sa clé protégée existante.
Le [parcours OAuth Android préparatoire](netatmo-oauth-android.md) ajoute un écran
natif et les contrôles de session/coffre simulables. **La connexion reste bloquée
sans service confidentiel autorisé et raccordé** : aucun secret client sur tablette,
aucune copie de clé vers un CLI externe, aucune application Netatmo créée par Codex.
Le renouvellement direct avec `client_secret` est réservé au serveur Linux ;
Android suspend les lectures à expiration tant que le service n'est pas raccordé.

Les appareils utilisent `<instance>:<home-id>.room.<room-id>` et
`<instance>:<home-id>.module.<module-id>` ; aucun nom ni ordre de tableau ne sert
à l'identité. Les noms fournis restent des suggestions, les étiquettes Moli
restent prioritaires. Les pièces/favoris existants dans `home.json` ne sont pas
réécrits. La préparation Android conserve l'instance manuelle, ses options et sa
désactivation ; un test ciblé le vérifie. Pas de nouvelle version de sauvegarde.

## État, affichage et erreurs

L'inventaire Système distingue le pilote compilé de ses instances configurées,
activées ou non. Le statut du pilote décrit le fonctionnement de la lecture API,
pas une certification matérielle. Les appareils et les points n'apparaissent
qu'après une réponse décodable ; ils ne sont jamais marqués vérifiés par le seul
manifeste. Leurs cartes dans Maison affichent des lectures, aucun contrôle.

`cloud_recent` exige des dates serveur non futures pour **les deux réponses**,
un âge dans la limite (la plus ancienne date gagne) et
`reachable=true`. `offline`, `stale`, `unconfirmed`, `missing` et `error` ne
conservent **aucune valeur de température/consigne/demande comme valeur courante** :
les points concernés passent à null, la demande à indéterminée et la disponibilité
à false. Une omission dans la réponse suivante efface aussi l'ancienne valeur.
Les identités et associations ne sont pas supprimées. Les dates de la dernière
collecte réussie restent visibles pour expliquer l'âge, jamais comme preuve actuelle.

Chaque réponse est bornée à 1 Mio et chaque échange TLS public à 10 s, hôte fixé à
`api.netatmo.com`, sans redirection ni URL configurée. Un ID de maison différent,
un doublon d'identité, un type incohérent, un JSON malformé ou une erreur de
synchronisation partielle invalide la collecte. Les corps d'erreur, jetons et URLs
ne sont pas envoyés dans les logs ou les statuts publics.

Un 401/403 en lecture invalide les valeurs immédiatement et demande un seul
renouvellement au prochain cycle, pas une boucle de requêtes. Si les credentials
ou la persistance sont refusés, pause d'une heure avant nouvel essai. Un couple
renouvelé non sauvegardé reste en mémoire uniquement pour retenter sa persistance,
sans nouveau renouvellement ni lecture préalable. Une fermeture du processus dans
cette fenêtre peut imposer une réautorisation officielle ; aucun ancien jeton ne
peut être garanti valide après rotation. Un 429
respecte `Retry-After` numérique ou HTTP-date (au moins la cadence choisie) ;
champ absent ou invalide : pause conservatrice d'une heure. Les énormes délais sont plafonnés
à sept jours. Les valeurs sont aussi invalidées sans nouvelle réponse à l'expiration,
avec horloge monotone en complément de l'heure UTC. L'UI vérifie à son tour les deux
dates et masque les valeurs si le moteur ou sa connexion ne confirme plus l'état.

## Vérifications et prochaine connexion réelle

Tests Rust sans réseau : demande true/false/absente, variantes, temps absent/futur/
périmé, injoignabilité, omissions, identité après renommage/réordonnancement,
Internet indisponible/reprise, 401 et renouvellement, quotas, réponses invalides,
rotation chiffrée atomique et blocage avant lecture si le coffre refuse l'écriture.
Test UI de fraîcheur/déconnexion et test de conservation de l'instance par la
préparation Android. Les preuves 0.18 restent acquises ; aucun APK reconstruit.

Avant accès au compte : accord distinct du propriétaire, application Netatmo et
redirect autorisés, consentement dans le navigateur officiel avec `state` aléatoire
lié à la session, scope **read_thermostat seul**, échange de code côté coffre
(sans exposer le client secret dans une WebView), puis provisioning protégé.
Ne pas inventer de redirect ni prétendre qu'un client secret intégré dans une APK
est confidentiel : le choix de l'application et de son échange sécurisé reste à
valider avant de raccorder le parcours Android préparé. Aucun flux password grant.

Après cet accord et le parcours Android : une lecture volontaire permet de
vérifier modèles/champs/liaison/temps effectivement fournis par le compte et de
comparer l'indication déclarée avec l'application Netatmo. Pas de changement de
consigne ni de commande de prise, pas de nouveaux essais de redémarrage exigés.
Révocation : désactiver l'instance, révoquer l'autorisation dans le compte officiel,
puis oublier ses deux secrets dans le coffre ; ne pas effacer les associations ou
la clé maître. La désactivation seule n'est pas une révocation côté Netatmo.
