# Préparer Netatmo Energy

1. Lire les [conditions et limites](../../docs/components/netatmo-energy.md).
   Ne pas demander de mot de passe et ne pas créer d'application à l'insu du propriétaire.
2. Obtenir un consentement distinct pour le compte réel et choisir une application/
   redirect officiels autorisés, scope `read_thermostat` seul. Le parcours navigateur
   et son provisioning Android restent à implémenter ; pas d'injection via fichier
   ou extraction de la clé Android pour le contourner.
3. Sur un serveur avec CLI, après le consentement officiel, ranger le couple de
   jetons et le secret d'application dans le coffre existant par tubes protégés.
   Ajouter l'instance Moli sans remplacer sa configuration ou son `home.json`.
4. Activer une seule instance pour la maison choisie. Vérifier les lectures et
   les champs présents, leur âge et leurs limites dans Maison/Système. Une réponse
   cloud récente n'est pas un horodatage de mesure, ni une preuve de chauffe physique.
5. Pour déconnecter : désactiver l'instance, révoquer côté Netatmo et oublier ses
   secrets. Garder les identités, associations, historique et clé maître.

Aucun essai ON/OFF, aucune modification de consigne, aucune sollicitation physique
n'est nécessaire pour les tests logiciels de cet incrément.
