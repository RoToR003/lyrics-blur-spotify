# Перевірка патчу через Morphe — покроково

Основний шлях — **Morphe Manager** на пристрої (саме застосунок, не CLI).
Опційний гейт без пристрою — §A (Morphe CLI; той самий патчер).
Рівні: **M1** збірка `.mpp` → **M2** джерело в Morphe → **M3** патч + встановлення → **M4** тести → **M5** діагностика.

Критерії приймання (усі обов'язкові):
1. `.mpp` зібраний; у `patches-list.json` є `Lyrics blur`.
2. Morphe: патч **успішно застосований** (немає екрана «Failed to apply Lyrics blur»).
3. Застосунок встановлюється і не падає; lyrics рендеряться.
4. Неактивні рядки розмиті, активний різкий; решта екранів незмінна.
5. Для релізу: `Debug logging` вимкнено.

---

## M0. Що потрібно

- Android-пристрій, **Android 12+** (нижче blur просто не вмикається).
- **Morphe Manager**: https://morphe.software або GitHub — `MorpheApp/morphe-manager/releases` (latest: `v2.8.0`, `morphe-manager-2.8.0.apk`; `.asc` — підпис).
- Spotify **9.2.4.2644** `.apkm` (APKMirror; version code звірте за ім'ям файлу).
- Джава 17+ для збірки `.mpp` (M1). Для лише-встановлення готового `.mpp` збірка не потрібна.
- `adb` (рекомендовано, для logcat) або термінал на ПК.

## M1. Збірка `.mpp`

Варіант 0 — **Releases → [bundle-latest](https://github.com/RoToR003/lyrics-blur-spotify/releases/tag/bundle-latest)** (rolling prerelease, оновлюється після кожної збірки на main і з ручного запуску workflow):
файл `patches-<версія>.mpp` **без** `-sources`/`-javadoc` у назві (≈4.5 MB).

Варіант 0-bis — **з Actions цього репозиторію**: кожен пуш збирає bundle;
відкрити Actions → останній ЗЕЛЕНИЙ run "Build .mpp" → артефакт `lyrics-blur-spotify-mpp` → завантажити
(всередині кілька `patches-*.mpp`: беріть той, що без `-sources`/`-javadoc`;
workflow сам перевіряє, що `patches-list.json` містить `Lyrics blur`).

Варіант 1 — **локально** (потрібні JDK 21, Android SDK, GitHub-токен `read:packages`):
```
./scripts/build-mpp.sh
```
(скрипт клонує southstar21-patches, копіює цей патч, додає `AppTarget("9.2.4.2644")`, збирає; результат у `./out/`)

Варіант 2 — **вручну в апстрімі**: 3 файли за `docs/integration.md` +
```
GITHUB_ACTOR=<you> GITHUB_TOKEN=<token> ./gradlew :patches:buildAndroid --no-daemon
```
Очікуємо: `patches/build/libs/patches-<версія>.mpp`.

## M2. Додати джерело в Morphe (один раз)

**Спосіб A — через файловий менеджер (найпростіше):**
1. Перекинути `patches-<версія>.mpp` на телефон.
2. Відкрити його — Morphe запитає: нове джерело → «Add patch source» (довіряєте? → підтвердити);
   оновлення вже доданого джерела → «This is a new version of %s. Update it?».
3. Джерело з'являється у списку (`Sources` на головному екрані).

**Спосіб B — у застосунку:**
1. Morphe → **Sources** → **Add** → «Add patch source».
2. Обрати **Local** → «Select patch source files» → вказати `.mpp` (ім'я обов'язково `.mpp`).

**Спосіб C — Remote** (автооновлення при релізах): Add → **Remote** → вставити URL джерела
(`https://github.com/southstar21dev/southstar21-patches` — якщо патч смерджать туди) або deep-link
`https://morphe.software/add-source?github=southstar21dev/southstar21-patches`.

Після додавання переконайтесь:
- у списку з'явився **Spotify 9.2.4.2644** (після `AppTarget("9.2.4.2644")` — як перевірений таргет).
  Якщо `Constants.kt` не редагували, 9.2.4.2644 буде **experimental**: у налаштуваннях джерела
  увімкніть **Enable experimental app versions** («Shows and patches experimental app targets»).
- **Expert mode** (Settings → Patching → Expert mode) НЕ обов'язковий для одного джерела; потрібен
  лише для комбінації з іншими джерелами (як GmsCore для YouTube Music).
- Якщо джерело показує бейдж **Crashed**/**Blocked**/**Key changed** — патч не застосовуйте,
  збережіть текст з підказки і зупиніться.

## M3. Патч і встановлення

1. Видалити встановлений Spotify з Play Store (підпис після патчу інший — поруч не встануть).
2. Головний екран Morphe → **Spotify** → обрати файл APKM (9.2.4.2644).
3. Екран патчів: увімкнути **Lyrics blur**; для першого тесту також **Debug logging**.
   Опції (Options біля патча): **Only for synced lyrics** (дефолт ON), **Debug logging** (дефолт OFF).
4. Кнопка патчингу → «Applying patches» → очікуємо екран успіху → **Install**.
5. Якщо бачите **«Failed to apply Lyrics blur»** — текст помилки і є діагностикою (таблиця M5).
   Оригінальний Spotify при цьому незмінений.

## M4. Перевірка в застосунку

| # | Крок | Очікування |
|---|---|---|
| 1 | Synced-пісня, lyrics на весь екран | неактивні рядки розмиті; активний різкий |
| 2 | Now Playing → lyrics | те саме |
| 3 | Спостерігати 10–15 с | blur «перетікає» ~200 мс за активним рядком, без смикань |
| 4 | Seek у середину пісні | той самий активний рядок — різкий; решта — розмиті |
| 5 | До початку / після кінця | активного рядка нема → усі рядки розмиті (при `Only for synced lyrics` = ON) |
| 6 | Не-timed текст, опція ON (дефолт) | розмиття немає |
| 7 | Те саме, опція OFF → перепатчити | усі рядки розмиті |
| 8 | Home / Search / Library / Now Playing без lyrics | без змін |
| 9 | Скрол lyrics, свайпи | без ANR/фриза |
| 10 | `adb logcat -c` → `adb logcat -s LyricsBlur:D AndroidRuntime:E *:S` | є рядки `line hook: active=false …` і `active=true …` |
| 11 | Офлайн/скачаний контент, звичайні екрани | працюють як до патча |
| 12 | Повний перезапуск застосунку | стабільно |

Логкат має сенс лише при увімкненому **Debug logging**.

## M5. Якщо «Failed to apply» / «не працює»

Де читати: екран результату патчу («Failed to apply %s» + details) → у `Sources` бейдж і підказка
джерела → `adb logcat`. Типові випадки:

| Симптом | Значення | Дія |
|---|---|---|
| `PatchException` … метод `Lp/ksh0;` не знайдено | fingerprint не зматчився (інша збірка / `.access flags` ≠ `public static final`) | надіслати `.access flags` і сигнатуру `ksh0.a` з JADX/smali саме цієї збірки |
| `expected one call …, found 0` / `>1` | якір `p.mtc1.f` змінився або переїхав на `/range` | повний smali `ksh0.a` |
| `uses vN, vM, expected v0, v15` | call site перекладено в інші регістри | повний smali `ksh0.a` |
| `VerifyError` при відкритті lyrics | інша регістрова схема | `FATAL EXCEPTION` + smali після патчу |
| Патч успішний, blur немає, логи `synced=false` | не-timed lyrics або невірна семантика `p2` | вимкнути «Only for synced lyrics»; якщо не допомогло — logcat + як виглядають lyrics |
| Blur є скрізь, включно з активним рядком | переплутано active/synced | logcat + smali `ksh0.a` (порядок операндів) |
| Джерело «Crashed»/«Blocked» | не та збірка `.mpp` або підроблений файл | перекачати `.mpp`, звірити sha256 з релізом |
| Не встановлюється поверх Play Store Spotify | різний підпис | видалити оригінал |

**Що надсилати при FAIL:** точні `version` + `version code` APKM · текст помилки з Morphe (скрін) ·
`adb logcat` з фільтром вище · smali `ksh0.a` до/після патчу · кроки відтворення.

## A. Опційний гейт без пристрою: Morphe CLI

Той самий патчер, що в Manager (CLI v2.2.0 = morphe-patcher 2.4.0). Використовуйте як CI-перевірку, не замість M3–M4.

```
gh release download v2.2.0 -R MorpheApp/morphe-cli -p '*-all.jar' -D .
mv morphe-desktop-2.2.0-all.jar morphe-cli.jar
java -jar morphe-cli.jar patch \
  -p patches/build/libs/patches-<версія>.mpp \
  -e "Lyrics blur" --continue-on-error \
  -r result.json -o spotify-patched.apk -t tmp/ \
  spotify_9.2.4.2644-*.apkm
jq '{success, applied: .appliedPatches, failed: [.failedPatches[].name]}' result.json
```
Прохід: `success=true`, `applied` містить `Lyrics blur`, `failed` порожній.
Глибокий чек: jadx → `ksh0.a` → перед `mtc1.f` стоять `invoke-static/range { p1 .. p2 } … shouldBlur(ZZ)Z` →
`move-result v2` → `invoke-static { v0, v2 }, Lp/lxs1;->k(Lp/xzo0;Z)Lp/xzo0;` → `move-result-object v0`.

## B. Перед релізом

- M2–M4 пройдені; **Debug logging вимкнено** (або перепатчити з дефолтом `false`).
- PR за конвенціями апстріму (`dev` ← гілка, `feat(Spotify): …`); `CHANGELOG.md`/`patches-list.json` не редагувати.
- У PR вказати version+code перевіреної збірки.
