# Netatmo — relecture bornée et diagnostics, code29

Le propriétaire a confirmé les lectures réelles avec code27 : températures,
consignes et demande déclarée visibles sur S5e. Il a signalé une attente prolongée
et des redémarrages manuels pour voir un changement. Le test de cycles successifs
sur code27 passe déjà : il ne suffit donc pas à attribuer ce retard physique à un
blocage déterminé. L’ancienne synthèse n’indiquait ni cadence effective ni échéance.

## Retour code28 et correction code29

Le retour réel code28 confirme une relecture `homesdata` en échec autour de
l’échéance normale, puis des redémarrages avec une lecture encore en cours.
**La cause physique n’est pas établie** : aucun motif réseau/API n’avait été
conservé dans l’export. Les tests de code28 ne prouvaient pas cette récupération.
Ne pas déclarer le jalon autonome terminé avant un cycle continu réel sur S5e.

Code29 conserve les deux GET officiels, la maison et la période normale configurée.
Après un échec réseau transitoire uniquement, le pilote reprend à **30, 60 puis
120 s** avec le réglage par défaut ; le plafond est la période configurée. Un
succès rétablit la cadence normale. Pas de rafale, restart, réautorisation,
contournement de `429/Retry-After` ou appel depuis l’export. Les erreurs API et
OAuth gardent leurs délais/protections existants.

Le transport Netatmo utilise le composant HTTPS public Rust `web_diagnostics` :
un budget total de 10 s couvre DNS, connexion TCP, TLS, en-têtes et corps borné.
Il ne suit aucun redirect et vérifie toujours le certificat. Les erreurs ne
contiennent que phase et indicateur de timeout ; requêtes, réponses et erreurs
brutes sont jetées. La tâche de connexion Hyper est arrêtée à la fin, y compris
lors d’une annulation. Les autres transports et pilotes ne sont pas modifiés.
Chaque étape est limitée à 15 s et le cycle complet à 60 s de temps runtime ;
les timers ne réveillent pas Android suspendu. Une rotation interrompue garde
son marqueur de sécurité ; une ancienne paire n’est pas rejouée automatiquement.

Le snapshot conserve fin de cycle, durée, échéance ayant déclenché le cycle,
**dernière cause d’échec indépendante du nouvel état**, et compteurs par catégorie
(DNS/TCP/TLS/en-têtes/corps, timeout, transport, refus OAuth, quota, format/API,
stockage). Un arrêt superviseur termine les étapes encore `Pending` en
`Cancelled`, distinct d’une panne fournisseur. Les données sont invalidées après
échec ; les anciennes demandes ne deviennent pas récentes grâce à un retry.

Le même journal d’essai cumule aussi les cycles et derniers cycles automatiques
observés entre remplacements du moteur. Il distingue ces totaux des compteurs du
hub actuel. L’époque des compteurs reste stable lors d’un redémarrage supervisé ;
une nouvelle observation identique ne double pas les chiffres. Dernière cause,
catégories et cumuls survivent au prochain essai, au succès, au redémarrage et à
l’élagage des événements. Un arrêt Android explicite ferme les observations en
cours même si l’ultime snapshot n’a pas pu être lu. Une fin jamais observée avant
une destruction brutale reste une limite du rapport en cache, pas une preuve API.
Les nouveaux champs sont fermés et n’enregistrent aucune identité ou mesure.

L’âge de communication affiche les secondes jusqu’à 59 s, `1 min 30 s` entre
60 et 119 s, puis des minutes compactes. L’horloge reste uniquement visuelle.

Preuves ciblées : première lecture réussie puis échec DNS `homesdata`, retry qui
reste muet, timeout, récupération automatique sans restart ni modification des
jetons ; annulation d’une lecture automatique ; timeout de cycle et époque
stable ; classification réseau/rotation ambiguë ; reprise/quota existants ;
conservation des erreurs malgré un snapshot raté, compteurs cumulés et zéro
champ privé dans le rapport ; seuils d’âge 59/60/90/119/120 s. La fixture WebView
exerce aussi l’âge mixte et le chemin snapshot → résumé après reprise. Les
résultats exécutés et la signature sont consignés dans le bilan de livraison.

## Planification

Le pilote Rust conserve le réglage existant, **120 secondes par défaut**, sans
modifier les configurations. L’attente utilise maintenant une échéance Tokio
explicite. Après succès, la période est mesurée entre débuts de cycles, au lieu
d’ajouter systématiquement la durée des requêtes. Un cycle trop long ou une reprise
tardive ne provoque pas de rafale de rattrapage. Les commandes restent refusées et
ne changent pas l’échéance ; aucun redémarrage automatique n’est ajouté.

Un contrôle d’expiration toutes les cinq secondes permet aussi de constater une
échéance UTC dépassée alors que l’horloge monotone n’a pas avancé autant. Cela
couvre une reprise après suspension ou une correction d’horloge ; **cela ne prouve
pas que Doze était la cause du cas utilisateur**. Un seul cycle normal reprend,
puis une nouvelle échéance est posée. Cette voie ne court-circuite jamais les
délais d’erreur, notamment `429` et `Retry-After`. Elle ne maintient pas la tablette
éveillée et n’ajoute aucune permission Android. Un système suspendu ou un réseau
interdit en arrière-plan ne peut pas garantir une communication à heure fixe.

Le même rapport d’essai conserve fréquence configurée, cycles automatiques,
dernier cycle, intervalle observé, prochaine échéance, retard, report et erreur.
Les données sont issues du snapshot local, jamais d’un appel fournisseur forcé par
l’export. Les dates et l’âge du snapshot permettent de distinguer une observation
ancienne d’une tâche bloquée ; l’état actuel du moteur reste indiqué. Les
transitions rapprochées sont regroupées, leurs compteurs et derniers états restent
dans le résumé, même si la liste d’événements est tronquée.

Les deux lectures par cycle restent inchangées : environ **60 appels/h/instance**
à 120 s, hors OAuth et autres clients du compte. Pas de passage automatique à 60 s
ni de cache de topologie à ce stade. Cacher `homesdata` demanderait notamment de
séparer l’âge des modes/programmes de celui des états ; un ancien mode ne doit pas
devenir récent grâce à une nouvelle réponse `homestatus`. Ni le quota disponible
ni la latence de propagation cloud ne sont garantis par cette cadence.

## Conservation du compte et affichage

Aucun effacement ou migration des secrets Netatmo n’a été trouvé sur le chemin de
mise à jour étudié. La vérification de lecture de l’écran compte est en mémoire et
peut être perdue après remplacement du processus, sans perte du coffre. L’écran
indique désormais explicitement l’autorisation et la maison conservées, et nomme
le bouton « Réautoriser » lorsqu’une paire existe. Une réautorisation inutile ne
doit pas être déduite de l’absence de cette vérification en mémoire.

La cause exacte de la reliaison rapportée reste non établie. Les fixtures de
remplacement d’APK sont étendues à l’application personnelle, aux jetons et à la
maison Netatmo : mêmes fichiers, clé, instance et statut stocké doivent survivre,
sans appel fournisseur. Un jeton réellement révoqué ou une rotation ambiguë peut
toujours demander une action explicite ; aucune de ces protections n’est levée.

Les cartes présentent température, consigne et demande **déclarée** au premier
regard, puis un âge compact de communication. Une horloge d’affichage partagée
rafraîchit cet âge sans requête. Les cartes de pièce et de thermostat précèdent
les accessoires. Les timestamps, autres points et explications sont dans des
détails consultables. Les valeurs périmées/non confirmées restent masquées, aucune
flamme ni demande issue d’un pourcentage n’est inventée.

Tests ciblés : plusieurs cycles avec changements sans restart, échéance après une
lecture lente, reprise sans rafale, respect du report quota, résumé persistant et
confidentiel, regroupement de traces et âge compact. L’exécution des fixtures
Android et la signature sont rapportées à la livraison. Le renouvellement réel
longue durée et la cadence physique S5e restent à observer sans imposer de nouvelle
connexion. Toujours aucune commande Netatmo, Meross/Finder ou chauffage.


## Code36 — actualisation volontaire

L’écran de commande propose une **vraie lecture Netatmo** via le cycle existant
`homesdata` + `homestatus`. Une seule demande peut attendre ; aucun scheduler,
SET ni changement du polling Meross. Minimum **60 s entre débuts** pour une
lecture volontaire ; le prochain cycle normal est recalé à 120 s. La lecture
manuelle ne contourne ni backoff réseau ni `429/Retry-After`. Le bouton distingue
lecture demandée/en cours/délai imposé, puis observe le résultat local sans
redemander au cloud. Le diagnostic compte `manual_cycles` et `MANUAL` séparément.

Références consultées : [guide développeur Netatmo](https://dev.netatmo.com/guideline)
et [API Energy](https://dev.netatmo.com/apidocumentation/energy). Le quota exact
applicable au compte n’est pas inspectable sans ses identifiants ; aucune API
réelle n’a été appelée par Codex. Les signalements du
[forum officiel](https://helpcenter.netatmo.com/hc/es/community/posts/29846852785298-Inconsistent-Rate-Limits-for-Netatmo-Home-Control-API)
montrent des limites effectives variables et des 429 concurrents. On conserve donc
120 s en fonctionnement courant, sans promettre de push `boiler_status` ni de
quota disponible. À 60 s au maximum, cette instance effectue 120 lectures de
données par heure (deux GET par cycle), hors renouvellement OAuth et autres
applications/instances du compte. Une attente fournisseur supérieure reste prioritaire.

Chevauchement CI vérifié : `android-integrations.yml` et `android-concordance.yml`
exécutent tous deux les six cas de `test-android-beta-update.sh` lorsque le plan
sélectionne `update=true`. Le premier scelle les preuves de l’APK candidate, le
second conserve la preuve indépendante de préservation. Leur réutilisation
exigerait un artefact attestant SHA/APK et une adaptation de la politique du portail
épinglée sur main. Aucun workflow ni portail n’est modifié dans code36 ; conserver
ces validations obligatoires et les caches JNI/plans par fichiers actuels.
