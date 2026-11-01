# Android Beta 2 code20 — navigation et présentation

Gestion intègre Automatisations au rail paysage et à la navigation portrait,
avec le même conteneur, titre, sélection et style que les autres destinations.
L'écran séparé code19 est supprimé ; son transport REST est extrait tel quel.
Le formulaire et les lectures en cours survivent aux rotations. Les états
proviennent de `live` et `approved` : active, inactive approuvée, brouillon.
L'approbation/activation reste dans l'éditeur Moli et la session humaine existante.
Graphes, sauvegarde conditionnelle/409, unicité, données et moteurs inchangés.

Retour Android lit la route locale actuelle : sous-page → parent, rubrique →
Accueil Moli, Accueil → Gestion. Une route inconnue revient à Accueil Moli.
Aucun parcours de l'historique WebView, aucun changement de route/composant Web
ni de durée de vie du moteur. L'origine et la génération du moteur sont vérifiées
avant toute navigation ; la restauration de route après rotation est conservée.

Vérification ciblée : trois états de règles préparés par l'API officielle sur
code19 (déclencheur manuel seul pour les versions approuvées, aucune action),
conservation coffre/fichiers/règles lors de 19→20, rail paysage/portrait,
rotation, retour formulaire/liste, retour Web parent malgré les écrans visités,
retour Gestion et règles identiques après consultation. Aucun essai matériel.
Aucun composant Moli officiel modifié dans ce jalon : seulement le shell Android.
