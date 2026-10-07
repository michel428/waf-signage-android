# WAF Signage — appli Android

Appli plein écran pour les boîtiers Android des écrans du club : animation de lancement,
association de l'écran, puis affichage de `https://signage.waf-forme.fr/player/?screen=…`.

## Créer l'APK avec GitHub (sans rien installer)
1. Crée un dépôt sur github.com (ex. `waf-signage-android`).
2. **Add file → Upload files** : glisse **tout le contenu** de ce dossier (y compris le dossier `.github`), puis **Commit**.
3. Onglet **Actions** : la compilation démarre toute seule (≈ 4 min).
4. Onglet **Releases** : télécharge `waf-signage.apk`.
   Lien direct toujours à jour (dépôt public) : `https://github.com/<ton-compte>/<dépôt>/releases/latest/download/waf-signage.apk`

Chaque nouveau commit recompile une nouvelle version, qui s'installe **par-dessus** l'ancienne
(même clé de signature `app/waf-signage.keystore` — ne pas la supprimer).

## Installer sur le boîtier
1. Installe l'APK (clé USB, ou navigateur du boîtier avec le lien direct ci-dessus).
2. Au premier lancement : **Nouvel écran** (code à saisir dans l'admin → Écrans → Associer un écran)
   ou **Écran existant** (liste des écrans déjà créés).
3. Toujours sur cet écran, active les deux pastilles :
   - **Démarrage automatique** → autoriser « Afficher par-dessus les autres applis » ;
   - **Lanceur par défaut** → choisir *WAF Signage* comme appli d'accueil.
   Les pastilles passent au vert quand c'est bon.

## Pendant l'affichage
- **Menu caché** : touche MENU de la télécommande, **appui long sur OK**, ou 5 tapotements dans le coin haut-gauche (écran tactile).
- Coupure réseau : écran « On se reconnecte… », l'affichage reprend tout seul.
- Le moteur web plante : l'appli repart toute seule.

## Réglages
- Adresse du serveur : `app/build.gradle` → `BASE_URL`.
- Écrans de l'appli (lancement, association, menu) : `app/src/main/assets/boot.html`.
