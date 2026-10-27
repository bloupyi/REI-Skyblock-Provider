# Diagnostic du conflit Skyblocker / REI

## Mode par défaut : isolation de l'intégration REI de Skyblocker

Skyblocker est déclaré comme dépendance optionnelle Fabric (`suggests`). L'option
du provider `disableSkyblockerReiIntegration` vaut **true par défaut**, y compris
lors de la migration d'une ancienne configuration sans cette option. Si le
provider est désactivé (`enabled=false`), l'isolation est également désactivée.

L'isolation exclut uniquement `SkyblockerREIClientPlugin` et
`SkyblockerREICommonPlugin` des collections de plugins fournies à REI. Leur type
est inspecté à l'exécution par réflexion, sans dépendance de compilation, sans
initialiser des classes optionnelles par `Class.forName` et sans écrire dans des
champs privés. Les autres plugins d'un même fournisseur sont conservés. Les
autres fonctionnalités de Skyblocker et ses fichiers de configuration ne sont
pas modifiés. Cela désactive leurs enregistrements de catégories, objets,
comparateurs, écrans et transferts auprès de REI ; cela ne décharge pas des classes
Java déjà chargées par Fabric.

En cas d'activation après utilisation du mode coexistence, les générateurs
Skyblocker que REI conserve deviennent immédiatement inertes à l'entrée de leurs
méthodes R/U. Le provider déclenche ensuite un rechargement complet de REI pour
reconstruire catégories, comparateurs et autres registres. Les générateurs ne
sont pas retirés arbitrairement de la liste globale.

Pour choisir le mode coexistence décrit ci-dessous : définir
`disableSkyblockerReiIntegration=false` dans `config/rei_skyblock_provider.json`,
puis utiliser `/reisbreload`. Le changement de mode est appliqué en jeu par un
rechargement REI automatique ; un redémarrage de Minecraft convient aussi.
`/reisbstatus` indique le mode et les classes isolées observées.

## Sources examinées

Skyblocker, branche principale, commit
[`ca1163e2b7309113cc4dd3c084829d4417606235`](https://github.com/SkyblockerMod/Skyblocker/tree/ca1163e2b7309113cc4dd3c084829d4417606235),
Minecraft 26.2 / REI 26.2.820. Ces observations concernent cette révision ; elles ne
prouvent pas le comportement d'une autre version installée chez un joueur.

Les six fichiers demandés ont été lus :

| Fichier Skyblocker | Observation |
| --- | --- |
| `SkyblockerREIClientPlugin.java` | Enregistre deux générateurs globaux, recettes et informations, avec une garde contre leur duplication. Catégories et générateurs sont conditionnés par `Utils.isOnSkyblock()` et `general.itemList.enableItemList`. `registerEntries` efface **toutes** les entrées REI avant d'ajouter les siennes. |
| `info/SkyblockInfoDisplayGenerator.java` | Une pile avec un ID SkyBlock suffit pour produire une fiche, sans attendre les recettes. Répond aussi à U avec cette fiche. Ne reconsulte pas la configuration. |
| `recipe/SkyblockRecipeDisplayGenerator.java` | Renvoie toutes les recettes/utilisations de `ItemRepository`, y compris une liste vide si l'index est vide ou l'identifiant ne correspond pas. Ne reconsulte pas la configuration. |
| `recipe/SkyblockRecipeDisplay.java` | Expose les entrées et sorties des recettes, avec leur quantité, et la catégorie fournie par la recette. Ne supprime pas les recettes d'autres fournisseurs. |
| `SkyblockItemComparator.java` | Hache `getNeuName()`, y compris les variantes de livres, familiers, runes et potions, ou renvoie le hash neutre. Il s'ajoute aux autres comparateurs globaux REI. |
| `SkyblockerREICommonPlugin.java` | Enregistre ce comparateur uniquement lors du rechargement REI, sous les mêmes conditions de serveur/configuration. |

`enableRecipeBook` est une autre option : la garde REI examinée utilise
`enableItemList`. Les générateurs déjà présents ne disparaissent pas au simple
changement d'une valeur. Le comparateur est reconstruit au rechargement REI, mais
REI 26.2.820 **conserve les générateurs globaux**, contrairement aux catégories.
Un rechargement seul peut donc laisser les générateurs actifs avec des catégories
absentes. Sans le correctif, le redémarrage complet est nécessaire pour garantir
leur disparition après désactivation.
La configuration examinée ne demande pas ce rechargement à la modification de
cette option. Le provider ne lit ni ne modifie le fichier de configuration de
Skyblocker.

`ItemRepository.getRecipes/getUsages` consulte les index de `NEURepoManager` par
`getNeuName()`. Une absence de données ou un identifiant différent donne zéro
recette. Les conversions non prises en charge sont écartées par Skyblocker.
Le générateur d'information peut continuer à répondre dans ces situations.

## Mécanisme et correctif en mode coexistence

REI cumule les résultats des générateurs ; il n'arrête pas leur parcours à la
première fiche. Une liste vide d'un générateur ne remplace pas les autres listes.
Les catégories `skyblocker:*` et `rei_skyblock_provider:*` sont distinctes.
Changer la priorité des plugins ne corrige donc pas une association de pile
échouée ni un index de recettes vide.

Le premier test client a également révélé et reproduit le défaut de cycle de vie :
après désactivation et rechargement, le générateur global Skyblocker est toujours
présent, sa catégorie a disparu et son display est encore renvoyé. La résolution
de catégorie dans le consommateur de `ViewsImpl` peut alors faire échouer toute
la recherche, y compris les résultats du provider. Les exceptions internes à
`getRecipeFor` sont protégées par REI, mais pas cette résolution ultérieure dans
le consommateur des résultats.

En revanche, `ViewsImpl.buildMap` n'effectue sa recherche **wildcard** des recettes
enregistrées que si tous les résultats sont vides. Une fiche d'information suffit
à empêcher ce secours. Les comparateurs globaux distinguent une pile portant un
ID SkyBlock d'une pile vanilla sans métadonnées ; d'autres comparateurs peuvent
aussi distinguer la pile d'inventaire de celle du dépôt. La recette peut donc être
présente dans le registre, échouer à l'association initiale, puis être absente du
résultat final parce que la fiche empêche la recherche de secours.

Le test client reproduit cette étape sur le vrai moteur de recherche REI : sans
notre secours, la recette enregistrée est absente quand les fiches sont visibles ;
elle réapparaît lorsque les autres résultats sont rendus invisibles, ce qui permet
à REI d'effectuer son secours. Avec le correctif, elle reste accessible avec les
fiches visibles.

Les protections sont les suivantes :

- Les recettes du provider sont obtenues directement depuis ses index par ID,
  indépendamment des comparateurs et de l'état du dépôt Skyblocker.
- `SkyblockFallbackGenerator` récupère les **displays enregistrés existants**
  correspondant au même matériau et au même ID SkyBlock. Une pile vanilla ne
  correspond que si le dépôt confirme son identifiant et son matériau. Deux
  épées différentes ne correspondent pas simplement parce qu'elles partagent un
  matériau. Les catégories, filtres de visibilité et fusion habituelle de REI
  restent appliqués aux displays d'origine.
- En mode coexistence, les plugins Skyblocker ne sont pas filtrés. Un mixin
  optionnel et ciblé intercepte seulement son `EntryRegistry.removeEntryIf`, dans
  `registerEntries`, pour conserver les entrées vanilla et celles des autres mods.
  Il ne supprime aucun générateur ou comparateur. Une version qui ne fait plus
  cette opération n'a pas besoin de cette interception (`require=0`).
- Un second mixin optionnel filtre les résultats des deux générateurs Skyblocker
  lorsque leur catégorie n'existe plus. Les générateurs ne sont pas supprimés ;
  leurs résultats redeviennent disponibles quand les catégories sont réenregistrées.
  Cela empêche les générateurs conservés par REI de faire échouer toute la recherche.
- Le provider n'enregistre plus de prédicat qui masque des recettes d'enclume
  d'un autre fournisseur.
- Chaque générateur du provider est enregistré une fois par registre. Chaque
  rechargement REI réenregistre ses catégories dans son propre espace de noms.
- Les doublons strictement identiques du provider sont écartés, y compris en
  parcourant une catégorie. Deux méthodes, coûts ou stations différents restent
  distincts. Aucune recette étrangère n'est retirée pour la dédupliquer.
- Les générateurs relisent le snapshot publié à chaque recherche. Ils ne gardent
  pas de résultat vide après un chargement incomplet ni d'anciennes recettes après
  une mise à jour. Un dépôt sans recette donne des informations, sans recette
  fabriquée pour combler le manque.
- R et U préfèrent une catégorie de recette réelle du provider lorsque les index
  en contiennent une, tout en respectant un filtre de catégorie explicite.

Ce mécanisme explique et corrige une perte vérifiable de recettes. L'incident
exact du joueur avec son binaire Skyblocker, ses données et ses réglages reste à
confirmer dans la matrice réelle ci-dessous : le test de simulation ne remplace
pas cette vérification.

## Diagnostic utilisable en jeu

Activer `debugLogging` dans **la configuration du provider**, puis exécuter
`/reisbreload`. Les recherches R et U journalisent : ID brut et résolu, état actif,
nombre de recettes et utilisations indexées, nombre de displays produits,
catégories, conflits entre les hashes de la pile recherchée et celle du dépôt,
et recettes enregistrées récupérées par le secours.

Les rechargements REI journalisent les catégories, les générateurs globaux et par
catégorie, ainsi que le nombre de comparateurs. La publication des données
journalise la révision, les quantités et les états de chargement/préparation.

`/reisbdebug HYPERION` et `/reisbdebug REFINED_DIAMOND` comparent les résultats du
générateur avec les résultats finaux de REI pour R et U. Le log indique les
catégories manquantes, les displays cachés par les prédicats et les recettes
absentes du résultat final. `/reisbstatus` donne l'état du chargement. Pendant un
rechargement REI, le diagnostic demande d'attendre sa fin.

Interprétation : zéro recette dans l'index signifie un problème de données ou
d'identifiant ; un index non vide mais zéro émission signale les options du
provider ; une émission sans catégorie ou rendue invisible signale cette étape
du registre ; une émission visible absente du résultat final signale l'étape de
recherche/filtrage/fusion REI. Le journal des comparateurs des recherches R/U
permet de comparer la vraie pile d'inventaire à celle du dépôt.

## Validation automatisée

Commandes, avec Java 25 :

```sh
./gradlew build compileGametestJava
./gradlew repositoryAudit -PrepositoryArchive=/chemin/neu-master.zip
./gradlew skyblockerIsolationAudit -PskyblockerJar=/chemin/skyblocker-6.11.0+26.2.jar
./gradlew runClientGameTest -PrepositoryArchive=/chemin/neu-master.zip
./gradlew runClientGameTest -PrepositoryArchive=/chemin/neu-master.zip -PcoexistenceMode=disabled
./gradlew runClientGameTest -PrepositoryArchive=/chemin/neu-master.zip -PcoexistenceMode=absent
```

Les tests unitaires couvrent R/U par ID moderne/ancien/provider, le crafting et la
Forge, les quantités, les doublons, les objets inconnus, l'absence de recette, les
chargements incomplets et les publications successives sans réenregistrement du
générateur. Ils couvrent aussi le filtrage des deux points d'entrée Skyblocker,
leurs sous-classes, les fournisseurs contenant plusieurs plugins, l'absence de
Skyblocker, la réversibilité du mode et la migration de la configuration.

L'audit `skyblockerIsolationAudit` charge et instancie les deux vraies classes de
plugins depuis le [JAR officiel Skyblocker 6.11.0+26.2](https://github.com/SkyblockerMod/Skyblocker/releases/tag/v6.11.0%2B26.2).
Il vérifie leur provenance dans le JAR, puis leur exclusion par le garde, sans
appeler leurs hooks REI. Ce test de binaire réel ne constitue pas un lancement
complet de Skyblocker dans Minecraft.

Les tests client utilisent Minecraft et REI réels avec un dépôt NEU réel et des
plugins **de simulation**, sans installer le vrai mod Skyblocker. Ils couvrent les
trois modes (hooks absents, actifs, désactivés), un changement d'option sans
rechargement puis avec rechargement REI, Hyperion, Aspect of the End, Refined
Diamond, les utilisations d'Enchanted Diamond, un objet sans recette, les écrans
de recette, les recettes vanilla et étrangères, leurs prédicats de visibilité,
les filtres de catégorie et une collision de comparateurs. Les touches R sur
Hyperion et U sur Enchanted Diamond sont également envoyées au vrai écran
d'inventaire ; les catégories ouvertes sont contrôlées. La suite existante
couvre également la publication/recharge des données sans redémarrage.
La suite client démarre maintenant avec l'isolation activée, vérifie que les deux
plugins sont exclus, réactive le mode coexistence pour les trois scénarios, puis
réactive l'isolation. Elle vérifie l'arrêt immédiat des anciens générateurs, le
rechargement REI automatique, l'absence de callbacks Skyblocker et la conservation
des autres plugins ainsi que des vraies touches R/U.

Résultats exécutés le **26 octobre 2026**, avec Java 25, Minecraft 26.2 et
REI 26.2.820 :

| Vérification | Résultat réel |
| --- | --- |
| `build` et `compileGametestJava` | Réussis ; JAR installable et archive du projet générés |
| Tests unitaires standard | 67 réussis, 0 échec ; 3 tests optionnels ignorés |
| `repositoryAudit` avec le ZIP NEU téléchargé | Réussi : 8 940 objets, 6 440 recettes, 0 avertissement |
| `skyblockerIsolationAudit` avec le JAR officiel 6.11.0+26.2 | Réussi pour les deux classes réelles |
| Suite client complète, isolation activée au démarrage | Réussie ; aucun callback des plugins Skyblocker simulés |
| Mode coexistence, hooks simulés absents/actifs/désactivés | Réussi, y compris les touches R/U |
| Activation de l'isolation après coexistence | Réussie : anciens générateurs inertes, rechargement REI automatique, autres plugins conservés, touches R/U fonctionnelles |
| Catégorie orpheline sans protection | Échec de toute la recherche reproduit volontairement puis intercepté par le test |
| Recherche protégée Hyperion | Recette + information présentes jusqu'au résultat final ; `lost=[]` |
| Recettes étrangères, vanilla et filtres de visibilité | Préservés ; les catégories masquées ne sont pas réintroduites par le secours |
| Mises à jour des données sans redémarrage | Suite `LiveSyncGameTest` réussie, y compris ajout, changement de quantité et retrait de recettes |

Les trois tests client existants (`ProviderGameTest`, `PlanningGameTest`,
`LiveSyncGameTest`) ont tous été exécutés. Un ancien test de planification qui
exigeait de cacher un display d'enclume a été remplacé par une assertion de
conservation, conformément au comportement demandé.

Les trois exclusions de la suite standard sont les audits du dépôt et du binaire
Skyblocker, exécutés séparément avec succès, et le test des prix sur les services
publics réels. Les tests client des prix utilisent des réponses locales
contrôlées. Aucun essai sur Hypixel avec le mod Skyblocker complet n'a été effectué.

Les journaux des lancements de coexistence et du nouveau mode d'isolation sont conservés dans
`build/reports/compatibility/` ; les rapports JUnit sont dans
`build/reports/tests/test/`, `build/reports/tests/repositoryAudit/` et
`build/reports/tests/skyblockerIsolationAudit/`.

## Matrice manuelle avec le vrai Skyblocker

Pour chaque ligne, utiliser R sur les trois objets cités, U sur Enchanted Diamond,
et R sur un objet que le dépôt indique sans recette. Vérifier les onglets, les
quantités et les ingrédients ; une fiche ne doit pas être l'unique résultat si
une vraie recette est présente. Vérifier aussi une table de craft vanilla.

| Configuration réelle | État |
| --- | --- |
| REI + provider, sans Skyblocker ni plugins de simulation | À tester manuellement |
| REI + provider + Skyblocker, paramètres par défaut | À tester manuellement |
| Même configuration, `enableItemList=true` | À tester manuellement |
| Même configuration, `enableItemList=false` | À tester manuellement |
| Redémarrage de Minecraft après chaque changement d'option | À tester manuellement |
| Rechargement des données et REI sans redémarrage, dans les deux états | À tester manuellement |

Conserver les numéros de version, `latest.log` avec le diagnostic activé et les
identifiants concernés pour distinguer un manque de données d'une recette filtrée.
