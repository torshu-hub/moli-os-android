# Netatmo — cadence autonome et cartes compactes, code28

Le propriétaire a confirmé les lectures réelles avec code27 : températures,
consignes et demande déclarée visibles sur S5e. Il a signalé une attente prolongée
et des redémarrages manuels pour voir un changement. Le test de cycles successifs
sur code27 passe déjà : il ne suffit donc pas à attribuer ce retard physique à un
blocage déterminé. L’ancienne synthèse n’indiquait ni cadence effective ni échéance.

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
