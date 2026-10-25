# Netatmo : priorité au moteur Android local, choix d'hôte encore ouvert

**Suite à un arbitrage personnel explicite du 25/10 :** le
[parcours Android expérimental local](netatmo-personal-android.md) est développé
sans attendre la réponse du fournisseur. L'analyse de conformité ci-dessous reste
valable ; elle n'est plus une condition d'arrêt de ce prototype réversible.

Évaluation du 25 octobre 2026. **Aucun déploiement, compte, application, secret
réel ou consentement n'a été créé ou utilisé.** Cette note complète l'étude
[OAuth commune](oauth-common.md) ; le backend séparé n'est pas le choix par défaut.

## Peut-on provisionner les identifiants après installation sur Android ?

**Techniquement, oui :** Rust peut exécuter le même échange OAuth et renouvellement
sous Android ou Linux, avec des identifiants individuels saisis après installation.
Cela évite de distribuer un secret commun à tous les APK. Les droits réseau ne
nécessitent pas un VPS ; les appels HTTPS peuvent partir directement de l'appareil.
Cette possibilité technique ne démontre pas l'acceptation du fournisseur.

La [documentation OAuth Netatmo](https://dev.netatmo.com/apidocumentation/oauth),
contenu public revérifié le 25/10 via `POST /api/getwysiwyglayoutjson`,
`file=connect/apidocumentation/oauth/file.json`, demande un secret conservé côté
serveur et exclut son exposition dans une application mobile ou un firmware.
Elle ne décrit pas d'exception pour une application personnelle provisionnée après
installation, ni PKCE/client public natif ou enregistrement dynamique de clients.
**Le modèle Android personnel n'est donc pas démontré conforme à ses exigences
publiées.** Ce n'est pas la preuve d'une impossibilité technique ou d'un refus
spécifique de cette topologie : une confirmation officielle est nécessaire.

La [RFC 8252, §8.4–8.5](https://www.rfc-editor.org/rfc/rfc8252#section-8.4)
distingue le secret partagé incorporé au logiciel et les mécanismes de secrets
propres à chaque installation, par exemple l'enregistrement dynamique. Cette
exception ne constitue pas une autorisation Netatmo. PKCE et le type de client
doivent également être acceptés par le fournisseur ; les ajouter au code ne suffit pas.

Le [Keystore Android](https://developer.android.com/privacy-and-security/keystore)
protège les clés cryptographiques, éventuellement dans le matériel, selon les
capacités vérifiées de l'appareil. Il ne rend pas automatiquement non extractible
un `client_secret` OAuth : avec `client_secret_post`, ce secret doit être déchiffré
en mémoire pour être envoyé dans le formulaire TLS. Un processus compromis peut
intercepter son usage. Un processus local distinct ou une application compagnon
réduit certaines expositions ; il ne transforme pas à lui seul le téléphone en
client confidentiel reconnu par Netatmo. Un processus Android `isolatedProcess`
n'hérite pas simplement de l'accès au coffre/Keystore de l'UID principal.

## Proposition locale, conditionnée à la réponse Netatmo

Si Netatmo confirme ce modèle, utiliser **une application personnelle distincte**,
des identifiants provisionnés localement après installation et le coffre Moli
existant protégé par Keystore, sans secret partagé dans le binaire. Le composant
Rust d'authentification reste derrière une interface privée, sans API LAN ni
nouvelle permission exportée par défaut. Ne pas ajouter Termux, un autre moteur
ou une application compagnon tant qu'une isolation supplémentaire n'est pas justifiée.

Ouvrir le navigateur système pour le consentement `read_thermostat` seul. Si le
fournisseur autorise un retour loopback, envisager par exemple
`http://127.0.0.1:8791/oauth/netatmo/callback` : **exemple non enregistré**, port et
format exacts à confirmer. Écouter uniquement sur loopback, réserver le port avant
l'ouverture du navigateur, contrôler redirect/state/session, consommer une seule
fois et fermer l'écoute après résultat ou expiration. Un App Link HTTPS demanderait
un domaine et une association Android vérifiée ; un schéma personnalisé non confirmé
ou un copier-coller de code nu ne remplace pas la validation du retour.

Après consentement : découvrir les maisons, faire choisir explicitement l'identité
native existante, puis préparer la configuration sans écraser associations ou options.
Un seul propriétaire sérialise renouvellement et écriture de la paire complète.
Lire avec le nouveau token seulement après sauvegarde ; conserver les résultats
de rotation pour reprise, arrêter sur issue d'envoi incertaine. Les restrictions
Android actuelles sur l'échange confidentiel restent en place jusqu'à cette revue.

## Comparaison des solutions

| Solution | Autonomie du moteur Android | Contraintes | Coût indicatif |
| --- | --- | --- | --- |
| Identifiants personnels après installation sur Android | Oui | Acceptation Netatmo, type de client, redirect et protection du code à confirmer. Application personnelle et consentement à autoriser ensuite. | Aucun hébergement ajouté ; réseau/énergie de l'appareil existant. Pas de gratuité contractuelle de l'API garantie par cette étude. |
| Moli sur Linux local, tablette interface | Non | Modèle serveur similaire à HA ; machine disponible en continu, coffre et sauvegardes, retour navigateur accessible et autorisé. Pas de migration décidée ici. | Sans abonnement logiciel imposé ; coût marginal nul de location si machine existante, plus électricité et éventuel matériel. |
| Petit composant d'authentification sur Linux local, moteur sur Android | Oui, avec dépendance locale pour renouveler | Variante de repli préservant le moteur Android : même service portable, HTTPS joignable depuis la tablette et son navigateur, disque persistant, maintenance. Ce n'est plus « sans autre serveur ». | Pas de location si hôte existant ; matériel/électricité éventuels. Aucun hôte existant supposé disponible. |
| Service HTTPS extérieur | Oui, avec dépendance extérieure pour renouveler | Dernier recours : opérateur de confiance, clé d'appareil, coffre et disque persistants, mises à jour, disponibilité et retrait d'accès. | Budget de comparaison d'environ 5–10 €/mois pour une petite VM, **estimation non contractuelle** ; devis, taxes, IP, domaine et sauvegardes à confirmer avant autorisation. |

Le modèle [Home Assistant / identifiants personnels](https://www.home-assistant.io/integrations/netatmo/#development--testing-with-your-own-client-id)
montre une application enregistrée puis des credentials fournis au serveur HA.
L'application Android qui affiche HA n'héberge pas normalement ce serveur ; cet
exemple ne prouve pas l'acceptation du secret dans une application Android locale.
Le cloud Netatmo et Internet restent nécessaires dans toutes ces options. Les
webhooks ne sont pas nécessaires aux lectures Energy par polling de cet incrément.

Pour un serveur personnel, la proposition minimale est **un processus Rust + le
coffre Moli existant sur disque persistant**, derrière le HTTPS existant ou
[Caddy avec certificats automatiques](https://caddyserver.com/docs/automatic-https).
Pas de Kubernetes, base distante, abonnement OAuth, moteur domotique supplémentaire
ou fournisseur imposé. Le retour Netatmo est une navigation du navigateur : un
serveur local doit être joignable par ce navigateur, sans exiger d'ouvrir le moteur
domotique sur Internet. Le DNS, la confiance TLS et le redirect restent à préciser.

Une offre gratuite à disque éphémère ne convient pas telle quelle :
[Render Free](https://render.com/docs/free) s'endort, perd ses fichiers aux
redémarrages et ne fournit pas de disque persistant. Une restauration de sauvegarde
ancienne ne remplace pas une rotation durable. Une offre gratuite avec stockage
transactionnel pourrait être évaluée séparément, sans changer le contrat Rust ;
aucune offre de ce type n'a été sélectionnée ni créée. Cloudflare est facultatif.

## Code conservé, utilisable pour tester sans compte

- `Broker::renew` et `Netatmo::with_broker` : raccordement du renouvellement au
  pilote existant, application/instance contrôlées, paire sauvegardée avant lecture.
- `RemoteBroker` : adaptateur Android/Linux HTTPS vers une origine explicitement
  provisionnée, clé d'appareil indépendante et données bornées. Aucun hôte par défaut.
- `confidential::service` : noyau facultatif de serveur personnel, qu'il soit local
  ou extérieur, avec transactions et rotations chiffrées, résultat récupérable,
  quotas durables et blocage après échange ambigu. Une clé d'appareil par compte ;
  pas de service multitenant ou d'authentification générale revendiqué.
- Routeur Axum séparé `/v1/netatmo/{begin,poll,cancel,renew}`, callback exact
  `/oauth/netatmo/callback`, retour statique sans code/token ; exemple de processus
  uniquement loopback `netatmo-broker`, **jamais lancé avec de vrais credentials**.
- `Grant::homes` : choix des maisons lu sur l'endpoint officiel, sans sélection
  automatique ni écriture de configuration.

Le serveur optionnel est exclu d'Android, même si `confidential-oauth` est activée.
Ce garde-fou est une décision d'implémentation actuelle, pas une preuve que le
scénario Android personnel serait techniquement impossible. Le noyau utilise
Rust/Moli/Tokio et le coffre existants ; une acceptation fournisseur conduirait à
revoir explicitement cet adaptateur local, sans remplacer le moteur.

**La production Android reste `Unconfigured`.** Le provisionnement approuvé et
son raccordement JNI/démarrage, la sélection non destructive de maison, les essais
TLS sur le futur hôte et les lectures réelles restent à terminer. Le noyau serveur
exige un propriétaire unique par compte ; pas de réplication multi-processus.
Une panne entre consommation fournisseur et sauvegarde définitive peut encore
imposer une réautorisation, jamais un rejeu aveugle. Ne pas considérer les tests
synthétiques comme une preuve de connexion sur appareil.

## Recommandation et décisions indispensables

1. **Comparer avant de choisir ; viser Android local d'abord.** Faire confirmer à
   Netatmo l'acceptation d'une application personnelle avec secret propre provisionné
   après installation, le type de client, le redirect local admis et le mécanisme
   de protection du code/PKCE. Aucun message n'a été envoyé au fournisseur.
2. Si cette topologie est acceptée, développer son adaptateur local et son
   provisionnement privé, puis demander les accords distincts d'application,
   credentials et consentement réel. Aucun abonnement ne serait ajouté par défaut.
3. Sinon, comparer d'abord le petit composant Linux **local** avec le moteur Linux
   complet ; conserver le serveur extérieur en dernier recours. Ne pas déplacer
   le moteur ou créer un service pour résoudre cette incertitude sans décision.
4. Autoriser ensuite une première connexion de lecture : vérifier `homesdata`,
   `homestatus`, renouvellement/redémarrage et `boiler_status` réel. Meross et
   l'automatisation attendent ce jalon ; aucune commande physique n'est permise ici.
