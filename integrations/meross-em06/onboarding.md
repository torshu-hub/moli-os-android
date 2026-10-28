# Onboarding : Meross EM06

1. Demander : l'adresse IP du compteur, et la clé du compte Meross qui l'a installé (la clé
   ne voyage jamais : elle signe les messages).
2. L'humain range la clé lui-même, par un tube : `moli-os secrets set <instance> key`.
3. Déclarer `kind = "profile"`, `profile = "meross-em06"`, `host = "<ip>"` ;
   `moli-os check-config`, redémarrer.
4. Tester : sans clé, statut `waiting` avec la commande à lancer ; avec la clé, les six
   circuits (`power_1` à `power_6`) ; un circuit absent reste absent, jamais 0. Lecture seule.

## Android et prises classiques

Le transport signé `Call::Meross` existe déjà dans `moli-profile` : POST local `/config`,
message `method=GET`, contrôle de réponse `GETACK`. Il peut être réutilisé pour une
lecture après identification du modèle et des espaces de noms réellement supportés.
Le profil EM06 décrit un compteur multicanal `ElectricityX` ; ce n’est pas un profil de
prise MSS ni de lampe. Ne pas lui attribuer leur compatibilité ni réutiliser ses
index/unités sans fixture appropriée.

Première étape pour une prise : modèle exact, puis capacités/fixtures expurgées ;
examiner `Appliance.System.All` et, si supporté, `Appliance.Control.Electricity`.
L’import Android reste volontairement sans secrets et refuse ce profil ; aucun
contournement ni nouveau contrôle électrique n’est ajouté. Un futur parcours de
clé locale devra utiliser le coffre du moteur et une saisie volontaire hors rapports.
Ne pas demander de clé avant d’avoir validé le modèle et ce besoin.
