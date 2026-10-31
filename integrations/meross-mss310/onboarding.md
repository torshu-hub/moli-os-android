# Configurer une MSS310

Gestion Android → Intégrations → Meross MSS310 : choisir une IP découverte dans
Réseau, ou saisir l'IP privée et le port HTTP (80 normalement), puis un nom.
Un port ouvert ne prouve pas la marque : l'enregistrement vérifie le modèle et
l'UUID via une réponse Meross signée avec la clé.

Saisir une clé connue, ou ouvrir Compte Meross, choisir la région, se connecter
volontairement puis sélectionner la MSS310. Si Meross réclame un code de
vérification, rouvrir ce dialogue pour le saisir. La clé et la session vont dans
le coffre Moli ; le mot de passe n'est pas conservé. Le cloud ne sert qu'au
provisionnement. Une clé manuelle permet de fonctionner sans compte.

Avec le compte connecté, appuyer sur « Retrouver mes prises ». La liste Réseau
existante est réutilisée, ou sa recherche habituelle est lancée si nécessaire.
Chaque IP candidate est interrogée par GET signé : seuls les UUID correspondant
au compte sont associés aux noms des prises. La liste indique IP et UUID vérifiés,
« Non trouvée » ou une ambiguïté. « Ajouter les prises trouvées » enregistre
ensemble les correspondances uniques dans la configuration Moli, sans deviner
les IP ni confirmer chaque prise. Les prises absentes restent absentes ; le
formulaire manuel permet de corriger une adresse manquante.

Enregistrer utilise la transaction `moli.toml` habituelle. Ajouter les autres
prises avec « Ajouter une MSS310 ». Réassocier le même UUID garde son instance,
son historique et ses références, même après changement d'IP ou de nom. Une clé
vide lors d'une modification conserve la clé chiffrée de cette instance.

Le switch du catalogue active le pilote, sans modifier l'état électrique.
Mesures et ON/OFF sont dans les cartes et commandes Moli OS habituelles. Un clic
explicite suffit. Les automatisations natives peuvent utiliser les mêmes points ;
aucune règle n'est créée automatiquement. L'application Meross reste indépendante
et ses temporisations sont conservées. Arrêter Moli ne met pas une prise sur OFF.
En cas de résultat incertain, relire l'état avant un nouvel ordre.

Linux : profil intégré `meross-mss310`, options `host`, `port`, `name` et
`vars.expected_uuid` vérifié par le protocole. Clé dans `<instance>/key` via
`moli-os secrets set`, jamais en TOML. Identité, gardes, historique et
automatisations restent les mécanismes Moli.
