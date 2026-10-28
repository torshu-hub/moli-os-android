# Netatmo personnel local — essai Android 0.19.0-beta.1 / code26

> Le propriétaire a confirmé l’authentification officielle et le choix de maison
> sur S5e avec code26. La mise à jour [lecture et diagnostics code27](read-diagnostics.md)
> conserve cette association : ouvrir l’accueil pour voir les cartes et leur état,
> sans refaire le parcours OAuth ci-dessous par défaut.

Le moteur Rust Moli reste sur Android, sans hébergement extérieur, application
compagnon ou second moteur. Les identifiants personnels sont saisis **après
installation** dans l'écran natif privé puis chiffrés dans le coffre Moli existant,
avec sa clé protégée par le Keystore. Aucun identifiant Netatmo n'est inclus dans
l'APK. Le mode est explicitement **expérimental personnel** : l'acceptation de
cette topologie par Netatmo reste non confirmée. La clé de chiffrement n'empêche
pas un processus compromis d'observer le secret lors de son utilisation.

## Parcours à essayer personnellement

1. Installer la bêta signée par-dessus la précédente, moteur arrêté, sans
   désinstallation ni effacement. Ne pas transmettre d'identifiants dans un chat.
2. Sur le [portail officiel Netatmo](https://dev.netatmo.com/), créer soi-même une
   application personnelle dédiée à cet essai, dans **My apps / Create**. Remplir
   ses propres informations. Enregistrer exactement le retour
   **`http://localhost/moli-netatmo`**, sans query, fragment, slash final ou autre
   port. Ne pas renseigner de webhook pour ces lectures. Si le portail refuse
   cette URI, arrêter ici et relever seulement le motif, sans clé ni URL sensible.
3. Dans **Paramètres → Compte Netatmo — lecture seule → Configurer mon application
   personnelle**, saisir son Client ID et son Client secret, accepter le caractère
   expérimental puis enregistrer. Les champs secrets ne sont pas restaurés dans
   l'état d'activité ni proposés à l'autofill ; l'écran est protégé des captures.
4. Cocher le consentement de lecture et ouvrir le navigateur. Le mot de passe du
   compte reste uniquement sur la page officielle Netatmo. Autoriser
   **`read_thermostat` seul**. Si Netatmo refuse le redirect ou le type de client
   avant le retour, annuler dans Moli et relever uniquement le message public.
5. Le navigateur revient vers localhost ; aucune page locale n'est servie, donc
   une page inaccessible est possible. Copier **l'adresse complète** affichée
   après redirection, revenir dans Moli et la coller dans le champ masqué, puis
   valider. Elle contient un code temporaire : ne pas la partager, la photographier
   ou la mettre dans un rapport ; fermer ensuite cet onglet. Un code nu est refusé.
6. Lire les maisons autorisées, sélectionner le nom **et l'ID** corrects, puis
   confirmer. L'option d'activation des lectures reprend l'état existant ; la
   cocher explicitement si nécessaire. Démarrer ensuite le moteur pour retrouver
   les données dans **Maison**. Aucune commande de chauffage ou de prise.

Les identifiants d'application restent dans le coffre après une déconnexion des
jetons, comme l'indique le dialogue. Pour changer d'application, déconnecter
explicitement l'instance, puis remplacer ses identifiants. Retirer aussi
l'autorisation depuis le compte Netatmo pour révoquer côté fournisseur.

## Ce que la redirection vérifie réellement

La [documentation officielle OAuth](https://dev.netatmo.com/apidocumentation/oauth)
prévoit un redirect cohérent entre demande, enregistrement et échange du code.
Elle ne fournit pas une garantie publiée pour notre application Android personnelle.
Le parcours de recopie de l'URL exacte `http://localhost/moli-netatmo` est documenté
dans le [fork Energy de référence, révision 4825e54](https://github.com/Brizgir/moli-os/blob/4825e545baaada90f0d78fcd940637011025ed46/integrations/netatmo/README.md),
qui rapporte sa qualification sur une installation réelle. C'est une référence
d'implémentation, **pas** la preuve que cette nouvelle application sera acceptée.

Cette bêta ne suppose ni listener loopback, ni port dynamique, ni schéma Android,
ni App Link non enregistrés. Elle utilise le navigateur et la recopie manuelle du
retour complet, avec comparaison exacte du redirect et du state aléatoire à usage
unique. Les tests synthétiques vérifient ces contrôles et le formulaire d'échange ;
la vérification du portail, du navigateur S5e et de l'application personnelle
appartient au premier essai volontaire. Codex n'a créé aucune application et n'a
effectué aucun consentement ou échange OAuth réel.

L'écran distingue identifiants absents, autorisation requise, paire stockée et
lecture récente vérifiée. « Connecté » signifie ici une réponse API récente pour
la maison sélectionnée, pas une preuve de flamme ou de contact électrique.
Les erreurs fournisseur reconnues (`invalid_client`, `invalid_grant`,
`redirect_uri_mismatch`) deviennent des messages statiques distincts. Les corps
d'erreur, codes et tokens ne sont jamais recopiés dans les logs ou diagnostics.
Un refus non corrélé au state ne termine pas une session légitime : annuler ou
laisser expirer, puis recommencer. L'expiration de session est de cinq minutes.

## Conservation et renouvellement

- Le même pilote `moli-netatmo` effectue les lectures ; la feature explicite
  `personal-oauth` ouvre la stratégie locale avec l'application du coffre. Linux
  peut utiliser la même stratégie ; le serveur confidentiel demeure facultatif.
- Le secret `oauth_tokens` garde son format access/refresh/expiration existant.
  La nouvelle paire est sauvegardée atomiquement avant lecture. Un marqueur de
  rotation durable précède l'envoi ; après résultat ambigu ou crash avant
  sauvegarde, une réautorisation remplace un rejeu incertain. Une paire en attente
  de sauvegarde est retentée sans nouvel échange. Quotas et fraîcheur restent gérés
  par le pilote. Ni anciens tokens HA ni secret partagé de SDK ne sont importés.
- La sélection prépare uniquement la ligne de l'instance Netatmo et ses options
  maison/application/activation. Le texte des autres pilotes, les commentaires,
  options inconnues, `home.json`, UUID, associations, historique et clé maître sont
  préservés. L'écriture utilise le journal existant, moteur arrêté, et refuse un
  snapshot modifié ou une autre maison déjà liée à cette instance.
- Les sauvegardes chiffrées existantes restent le moyen de récupération du coffre ;
  aucune exportation en clair des nouveaux credentials n'est introduite.

## Preuves et limites du candidat

Tests ciblés Rust : callback complet/host/state/rejeu/refus/expiration, formulaires
officiels, erreurs statiques, rotation locale et reprise du pilote, suspension
après envoi ambigu. Tests JNI : provisioning chiffré, instance absente/existante,
choix de maison sans réactivation implicite, commentaires/options/associations
préservés et sauvegarde atomique. JVM : règles de présentation et URI exactes.
Une fixture Android isolée exerce le vrai provisioning Java/JNI/coffre sans
appeler Netatmo ; elle accompagne le parcours guidé déjà sélectionné par le gate.

La signature, les contrôles de mise à jour et les résultats de la chaîne sont
rapportés à la publication du candidat. L'essai sur compte réel doit encore
vérifier les deux réponses `homesdata`/`homestatus`, le `boiler_status` du thermostat
et le renouvellement effectif. Une incertitude de conformité fournisseur persiste ;
aucune régulation, consigne, commande Meross/Finder ou automatisation n'est activée.
