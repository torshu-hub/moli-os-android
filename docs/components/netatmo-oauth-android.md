# Netatmo OAuth Android : préparation, service confidentiel requis

Le parcours natif est préparé dans **Paramètres → Compte Netatmo — lecture seule**.
**La connexion réelle reste bloquée** : aucune application Netatmo, URL de service,
redirect enregistré ou identité de serveur n'est fourni dans ce dépôt. Aucun compte
n'a été connecté et aucun APK n'est produit pour montrer un bouton désactivé.
Le bandeau supérieur et les cartes Maison restent inchangés dans ce lot.

## Exigences officielles vérifiées le 25 octobre 2026

La [documentation OAuth officielle](https://dev.netatmo.com/apidocumentation/oauth)
décrit l'Authorization Code Grant et impose un **client secret confidentiel côté
serveur** ; elle interdit son exposition dans les applications mobiles ou le firmware.
Elle ne documente ni un enregistrement de client Android public sans secret, ni PKCE
pour remplacer cette exigence. Cela ne prouve pas qu'aucune autre offre n'existe ;
une confirmation officielle serait nécessaire avant d'en utiliser une.

Le navigateur doit atteindre `https://api.netatmo.com/oauth2/authorize`, avec le
`client_id` public, le redirect exact enregistré, le scope demandé et un `state`
aléatoire à vérifier au retour. L'échange du code exige `client_secret`,
`redirect_uri` cohérent et scope. Le renouvellement exige aussi le secret ; les
nouveaux access/refresh tokens remplacent ensemble les anciens. Moli demande
uniquement **`read_thermostat`**, sans scope d'écriture ni password grant.

La page est rendue côté navigateur ; son texte public a été vérifié via
`POST /api/getwysiwyglayoutjson`, champs `file=connect/apidocumentation/oauth/file.json`
et `container` vide, sans authentification ou compte utilisateur.

## Parties implémentées et testables sans compte

- Écran Android natif non exporté, protégé des captures, consentement explicite,
  choix de l'instance Netatmo existante, états d'attente/refus/expiration/stockage,
  annulation et déconnexion locale. Aucun champ de mot de passe ou secret client.
- Ouverture du **navigateur système**, uniquement vers l'URL officielle autorisée
  avec les paramètres exacts de la politique de déploiement. Aucun login dans la
  WebView. Aucune nouvelle permission ou route Android exportée.
- Le **redirect Netatmo vise le service HTTPS enregistré**, pas une URI Android
  inventée. Au retour manuel du navigateur, la reprise de l'écran vérifie la
  session native via le port Rust. Aucun code ou token n'entre par un Intent.
- Session Rust conservée uniquement en mémoire : état et preuve aléatoires de
  256 bits, cinq minutes au maximum (âge UTC et horloge monotone), abandon après
  fermeture du processus, annulation et rejet des transitions/rejeux inattendus.
  Seul un booléen d'attente passe dans le Bundle Android ; URL, nonce, preuve et
  jetons ne sont jamais enregistrés dans les préférences ou l'état d'activité.
- Port `Broker` séparant le client mobile du service confidentiel. La preuve de
  remise Moli lie une transaction à son client ; **ce n'est pas du PKCE Netatmo**.
  La seule implémentation de production actuelle est `Unconfigured` : aucun hôte
  arbitraire, secret ou token ne peut être injecté par le formulaire/JNI.
- Primitives **optionnelles côté serveur** (`confidential-oauth`, exclues d'Android
  même avec cette feature) : callback exact, vérification de `state`, consommation
  unique du code, échange officiel borné en HTTPS, renouvellement, validation du
  couple complet et du scope, remise à usage unique liée à sa preuve. Ce sont des
  briques de service, pas un serveur HTTPS déployé ou prêt à exposer sur Internet.
- Stockage des deux jetons dans le même secret `oauth_tokens`, format du pilote
  existant inchangé. Une erreur de sauvegarde conserve l'ancien coffre et la
  nouvelle paire en mémoire pour retenter la même écriture avant expiration.
  Les tokens et codes ont un Debug masqué et ne passent jamais dans les statuts JNI,
  les textes UI, les logs ou une URL de WebView.

Aucun nouveau flux ne commence sans consentement ; le statut local indique
la **présence de credentials**,
jamais une lecture API ou une connexion matérielle prouvée. L'écran reste explicite
sur cette différence après une réouverture ou restauration.

La documentation Netatmo illustre un refus sans `state`. Un callback non corrélable
est rejeté, y compris lors d'un refus : il ne termine pas une session légitime.
L'utilisateur peut annuler ou laisser expirer ; aucun succès n'est déduit d'un
retour de navigateur ou d'un Intent.

## Conservation des données et déconnexion

Les opérations de coffre restent dans la file existante `EngineHost` ; enregistrer
ou oublier un accès nécessite le moteur arrêté. Aucun démarrage, arrêt ou
réactivation automatique d'équipement n'est ajouté. Le code ne réécrit ni
`moli.toml`, ni `home.json`, ni étiquettes, associations, UUID, options, états
`enabled=false`, journal ou clé Keystore. L'identité sélectionnée doit correspondre
à une instance Netatmo ; un nom déjà occupé par un autre pilote est refusé. Une
transaction de configuration encore en attente bloque l'opération et conserve
son journal de récupération intact.

La déconnexion efface uniquement le secret `oauth_tokens` de l'instance choisie.
**Elle ne révoque pas l'autorisation côté Netatmo.** L'écran demande également de
retirer l'application des autorisations du compte officiel. Aucun endpoint de
révocation non documenté n'est inventé, aucun ancien secret d'un autre pilote n'est
supprimé. La configuration et les associations restent disponibles.

Le renouvellement confidentiel Linux du pilote est conservé. Sur Android, la
branche qui lirait `client_secret` est exclue de la compilation ; tant que le
renouvellement via le service approuvé n'est pas raccordé, une expiration suspend
les lectures. **Ne pas activer une connexion Android réelle avec des jetons copiés
manuellement ou un secret client placé dans le coffre mobile.**

## Ce qui reste avant une connexion réelle

**Décision du propriétaire :** choisir/autoriser un service confidentiel contrôlé,
son origine HTTPS et l'application Netatmo à utiliser (existante ou création
explicitement autorisée), avec un redirect qu'il contrôle et qui sera enregistré
exactement. Aucun domaine, compte ou application n'a été créé par Codex. Le secret
client sera provisionné uniquement sur ce serveur, jamais transmis dans le chat,
le dépôt ou l'APK. Une application commune et un serveur géré ont des implications
d'hébergement/accès aux données à approuver avant tout déploiement.

**Travail restant à Codex après ce choix :** réaliser le transport HTTPS et
l'authentification/appairage des appareils, le gestionnaire de callbacks HTTP,
la persistance serveur des transactions/rotations et leur liaison au compte/client,
puis brancher le renouvellement du pilote Android. Le port et les primitives sont
simulés, mais leur présence n'est pas une preuve de sécurité d'un service externe.
Il faudra aussi proposer la maison obtenue après consentement et activer ses
lectures par une préparation de configuration non destructive. Ces décisions et
ce raccordement précèdent toute bêta utile de connexion.

**Puis consentement utilisateur distinct :** ouvrir la page officielle depuis la
S5e et autoriser `read_thermostat` seul. La première lecture réelle devra confirmer
les champs et leur fraîcheur sur ce compte. Aucun mot de passe à donner à Moli,
aucun changement de consigne ou test ON/OFF. Les redémarrages et lectures 0.18 déjà
confirmés ne sont pas à répéter pour cette préparation.

## Vérifications proportionnées

Tests synthétiques du nouveau module OAuth, des primitives de service et du pont
JNI : approbation/refus, state absent/forgé, redirect forgé, expiration et rejeu,
remise liée à sa preuve, scope trop large, paire partielle, fermeture/réouverture,
coffre indisponible puis reprise, ancienne paire et autres secrets conservés,
configuration/associations/étiquettes/journal inchangés. Tests JVM du présentateur
Android et validation de ses URI, compilation Java et lint. Contrôle Rust ARM64
Android avec la feature serveur activée pour vérifier son exclusion par plateforme.

Les 16 tests du premier incrément sont réutilisés ; seuls les cas de rotation
concernés par l'exclusion du secret mobile sont repris. Aucun émulateur ou build
APK n'est nécessaire à ces contrôles ; les workflows de livraison restent inchangés.
