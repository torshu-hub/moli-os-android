# Lectures Netatmo et rapport d’essai — 0.19.0-beta.2 / code27

La connexion officielle et le choix de maison ont été **confirmés par le
propriétaire sur S5e avec code26**. Cette mise à jour conserve les identifiants,
le coffre, l’instance et son activation ; aucune nouvelle connexion n’est requise
par défaut. Aucun consentement ou accès à un compte réel n’a été effectué par Codex.

## Accueil et chemin réel

La sélection de maison prépare la seule instance Netatmo, puis le journal de
configuration existant écrit son état d’activation. Le moteur démarre uniquement
les instances activées. Les lectures Rust `homesdata` et `homestatus`, leur parsing
et leur publication sont maintenant observables séparément dans le snapshot
authentifié `/api/devices`, y compris avec zéro équipement. Les flags de
configuration/coffre/maison restent disponibles pour une instance désactivée.

L’accueil affiche automatiquement une section Netatmo à partir du hub, sans
modifier les pièces, favoris ou associations `home.json`. Les cartes existantes
affichent les points réellement publiés : température, consigne, modes, maison,
programme et demande de chauffe déclarée lorsque disponibles. Une valeur absente
reste absente ; données périmées, injoignables ou non confirmées restent masquées.
La réponse cloud n’est pas une date de mesure physique ; le booléen du thermostat
n’est pas une preuve de flamme. Aucun pourcentage de vanne ne devient un ON/OFF.

Avec zéro appareil, la section distingue autorisation **enregistrée**, maison
sélectionnée, activation, attente du premier relevé, erreur et dernière réponse.
Le statut `running` seul n’est jamais la preuve d’une mesure reçue. Une instance
explicitement désactivée n’est pas réactivée par une migration ; son état et
l’action utile sont présentés, sans lancer de nouvelle authentification.

## Système

Le graphe était placé dans la condition de comparaison avec Home Assistant.
Sans historique comparatif sur Android, le chargement restait donc visible alors
que `/api/system` avait répondu. Le graphe dépend désormais de la réponse Système
seule. La comparaison reste facultative. Le code évite aussi `Array.at/findLast`,
et borne à huit secondes les en-têtes **et le corps** des lectures. Échec réseau,
HTTP, format, dépassement ou rendu ont une catégorie statique et une reprise.
L’historique d’échanges assistant indisponible ne reste pas en chargement infini.

## Même rapport, schéma fermé

`TrialLog` / `TrialJournal` / `ReportExport` restent le seul export volontaire.
Le moteur publie des compteurs et statuts typés ; le moniteur Android lit seulement
son snapshot local, sans requête fournisseur supplémentaire. Les étapes sont :

- configuration, autorisation enregistrée/API confirmée, maison, pilote et jetons ;
- tentatives/succès/échecs et dernières dates de `homesdata`, `homestatus`, parsing
  et publication ; nombre d’appareils publiés puis présents dans le snapshot ;
- présence de températures/consignes courantes, disponibilité du champ booléen
  `boiler_status` et indétermination, sans enregistrer leurs valeurs ;
- lectures UI devices/Système, rendu de la tuile, erreurs JavaScript et historique
  assistant ; compteur, dernier succès/erreur et chargement non terminé.

Le pont de diagnostic du WebView utilise uniquement des codes numériques bornés
dans des messages console fixes ; aucune interface JavaScript donnant accès au
coffre n’est ajoutée. Les autres messages et erreurs console ne sont pas recopiés
dans logcat. Une catégorie d’erreur peut être observée sans son texte sensible.

Un résumé durable est conservé dans le journal existant et ajouté à chaque
export avec l’âge des observations, version/code et version majeure du WebView.
L’export n’effectue aucune requête cloud. Il distingue un accueil non observé
d’un appareil déjà publié dans le hub ; cela ne suffit pas à affirmer un bug de
rendu si l’utilisateur n’a pas ouvert l’accueil. Les transitions seules alimentent
les événements ; les compteurs agrégés restent disponibles après troncature.
Les écritures des diagnostics d’un snapshot sont groupées. Les contrôles de santé
identiques ont un bilan au plus toutes les cinq minutes, avec transitions conservées.

Le schéma n’accepte ni réponse brute, URL, exception, nom, identifiant de maison ou
module, Client ID, secret, jeton, IP/MAC ou mesure personnelle. Les identifiants
utilisés localement pour rapprocher les appareils ne sont pas conservés dans ces
résumés. Aucune télémétrie ni téléversement automatique. Cette même discipline doit
accompagner les prochaines fonctions Meross et Hyundai, avec des étapes adaptées.

## Limites et preuves

Les tests ciblés couvrent pipeline Rust, absence d’appareil, échec de parsing sans
publication, confidentialité des résumés, anti-répétition, reprise de compteur
après redémarrage, données périmées et délais UI. La fixture émulateur de
`EngineDiagnosticsTest` utilise des réponses WebView synthétiques pour la tuile,
le refus réseau avec zéro appareil, le timeout Système et son retour au graphe
sans comparatif HA. Elle n’appelle pas Netatmo et ne modifie pas la configuration.
Sa réussite et la signature sont rapportées à la livraison, sans les anticiper.

La lecture et le renouvellement **réels** sur le compte doivent encore être
observés sur S5e. Si les mesures restent absentes, le rapport identifiera l’étape
et les catégories disponibles, sans nécessiter par défaut de reset, nouvelle
connexion, nouvelle application ou collecte de payload privé. La conformité
fournisseur du mode personnel reste non établie. Toujours aucune commande de
chauffage, consigne, prise Meross/Finder ou automatisation physique.
