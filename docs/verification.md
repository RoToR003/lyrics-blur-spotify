# Перевірка патчу — покроково

Мета: підтвердити, що «Lyrics blur» збирається, застосовується до Spotify 9.2.4.2644 і працює на пристрої.
Рівні: **L1** статика APK → **L2** збірка → **L3** CLI-патч (без пристрою) → **L4** пристрій. Усі чотири — для релізу.
Тон: інструкція, без води.

Критерії приймання (усі обов'язкові):
1. Bundle збирається; у `patches-list.json` є `Lyrics blur`.
2. CLI-патч: `success=true`, `failedPatches` порожній, патч у `appliedPatches`.
3. Встановлене застосунок не падає; lyrics рендеряться.
4. Неактивні рядки розмиті, активний різкий; перехід плавний; не-lyrics екрани не змінені.
5. У збірці/конфізі релізу `debugLogging=false`.

---

## L0. Що потрібно

- Машина: JDK 17+ (збирально; для CLI достатньо JRE 17+), git.
- GitHub-токен з `read:packages` (для GitHub Packages Morphe) — `GITHUB_ACTOR`, `GITHUB_TOKEN`.
- Spotify **9.2.4.2644** `.apkm` (APKMirror; ім'я файлу містить version code; перед стартом звірте, що це саме 9.2.4.2644, не новіша збірка).
- Для L4: Android ≥ 12 (blur), USB debugging, ~500 MB вільного місця.
- Репозиторії: цей (джерела) + `southstar21dev/southstar21-patches` (ціль інтеграції).
- Опційно, для L3: `jq`.

## L1. Статика APK (опційно, але рекомендовано до L2)

1. `.apkm` — це zip: `unzip -l spotify_*.apkm` → вийняти `base.apk`.
2. JADX-GUI → відкрити `base.apk` → Text search (`Ctrl+Shift+F`, режим «Code»):
3. Перевірити (очікувані значення з дослідження):

| Що | Очікування | Якщо ні |
|---|---|---|
| `lyrics line scale` | веде в `p.ksh0.a` | патч тут не застосуємо; стоп, ескалація |
| `.access flags` методу `ksh0.a` | `public static final` (тільки ці біти) | див. L7 |
| `.registers` / `ins` | `80` / `18` | див. L7 |
| Викликів `p.mtc1.f(Lp/xzo0;F)Lp/xzo0;` у `ksh0.a` | рівно 1, аргументи `v0, v15` | див. L7 |
| Наступна інструкція після якіра | `iget v2, …` | регістр `v2` зайнятий; див. L7 |
| `p.lxs1.k(Lp/xzo0;Z)Lp/xzo0;` | існує | патч безглуздий; стоп |

4. Зафіксувати знайдене (копія `ksh0.a` smali) — знадобиться при розбіжностях.

## L2. Перенесення і збірка

1. Перенести 3 файли за `docs/integration.md` (копія + `AppTarget("9.2.4.2644")` у `Constants.kt`).
2. Збірка (як у апстрімі `README.md → Development`):
   ```
   GITHUB_ACTOR=<you> GITHUB_TOKEN=<token> ./gradlew :patches:buildAndroid --no-daemon
   ```
3. Очікуємо: `BUILD SUCCESSFUL`, артефакт `patches/build/libs/patches-<версія>.mpp`.
4. `./gradlew generatePatchesList` → `grep -c "Lyrics blur" patches-list.json` ⇒ `1`.
5. `unzip -l patches/build/libs/patches-*.mpp` — не помилка; вміст: dex/jar патчів і ресурси (структура — Morphe bundle).
6. Попередження компілятора (deprecation тощо) — допустимі тільки warning; error = стоп.

## L3. CLI-патч (без пристрою; найсильніший gate до L4)

1. Завантажити Morphe CLI (збірка на `morphe-patcher 2.4.0`, той самий патчер, що в Manager 2.6.0):
   ```
   gh release download v2.2.0 -R MorpheApp/morphe-cli -p '*-all.jar' -D .
   mv morphe-desktop-2.2.0-all.jar morphe-cli.jar
   ```
2. Пропатчити (увімкнено лише наш патч):
   ```
   java -jar morphe-cli.jar patch \
     -p patches/build/libs/patches-<версія>.mpp \
     -e "Lyrics blur" --continue-on-error \
     -r result.json -o spotify-9.2.4.2644-patched.apk -t tmp/ \
     spotify_9.2.4.2644-*.apkm
   ```
3. Перевірка результату:
   ```
   jq '{success, applied: .appliedPatches, failed: [.failedPatches[].name]}' result.json
   ```
   Прохід: `success == true`, `applied` містить `Lyrics blur`, `failed` порожній.
4. Глибокий чек (рекомендовано): `jadx spotify-9.2.4.2644-patched.apk` → знайти `ksh0.a` →
   у тілі безпосередньо перед викликом `Lp/mtc1;->f(Lp/xzo0;F)Lp/xzo0;` мають бути:
   ```
   invoke-static/range { p1 .. p2 }, Lapp/southstar21/extension/spotify/lyricsblur/LyricsBlurPatch;->shouldBlur(ZZ)Z
   move-result v2
   invoke-static { v0, v2 }, Lp/lxs1;->k(Lp/xzo0;Z)Lp/xzo0;
   move-result-object v0
   ```
   Це доводить наскрізний шлях «патч → extension → Spotify» до запуску.
5. L3 ≠ L4: CLI доводить застосування патчу, але не візуальний ефект і не стабільність.

## L4. Пристрій

1. Підготовка:
   - видалити/відв'язати Play Store Spotify (патч змінює підпис — поруч не встане);
   - зібрати `.mpp` прогнати за L3 (щоб не гадати, чи проблема в коді чи в пристрої);
   - у Manager: локальний `.mpp` → Spotify 9.2.4.2644 `.apkm` → увімкнути **Lyrics blur** + **Debug logging** (тимчасово) → Patch → Install.
   - якщо встановлення через adb: для патченого APKM результат Manager — готовий набір APK; ставити його ж, а не голий `patched.apk` із L3, якщо не впевнені щодо splits.
2. Матриця тестів (для кожного пункту — відзначити OK/FAIL):

| # | Крок | Очікування |
|---|---|---|
| 1 | Відкрити Spotify, відтворити **timed** пісню, відкрити lyrics (fullscreen) | неактивні рядки розмиті; активний різкий |
| 2 | Now Playing → lyrics | те саме (той самий composable) |
| 3 | Слідкувати 10–15 секунд | підсвітка переходить рядками; blur перетікає (~200 мс), без смикань |
| 4 | Перемотайте в середину пісні | після перескоку стан blur узгоджений (той самий рядок різкий) |
| 5 | До початку / після кінця пісні (активного рядка нема) | усі рядки розмиті (при `synced=true`) — очікувано |
| 6 | Текст без таймінгу (unsynced), опція `Only for synced lyrics` = ON (дефолт) | розмиття НЕМАЄ |
| 7 | Те саме, опцію OFF в Manager, перепатчити | усі рядки розмиті |
| 8 | Екрани Home / Search / Library / Now Playing без lyrics | візуально без змін |
| 9 | Скрол lyrics + свайпи між рядками | без ANR/jank; список реагує |
| 10 | `adb logcat -c && adb logcat -s LyricsBlur:D AndroidRuntime:E *:S` | є рядки `active=false … synced=true` і `active=true … synced=true` |
| 11 | Мережеві операції (скачування, офлайн-mode) | не змінювались (патч косметичний) |
| 12 | Свіжий перезапуск застосунку | стан стабільний, крашів нема |

3. Реліз-гейт: вимкнути **Debug logging** (або перепатчити з дефолтом `false`), повторити тест №1–2 швидко.

## L7. Якщо не зійшлось — діагностика

| Симптом | Значення | Дія |
|---|---|---|
| CLI/Manager: `PatchException`, «method not found» (lyricsblur) | fingerprint не зматчився (інша збірка/інший `.access flags`) | увімкнути `--continue-on-error` не допоможе; надіслати `.access flags` і сигнатуру `ksh0.a` з smali цієї збірки |
| `expected one call …, found 0` / `found 2+` | якір змінився/переїхав на `/range` | надіслати smali `ksh0.a` повністю |
| `uses vN, vM, expected v0, v15` | call site re-registered | надіслати smali `ksh0.a` повністю |
| CLI: `success=false` без наших помилок | конфлікт з іншими патчами | ізолювати: прогнати тільки `Lyrics blur` (`-e "Lyrics blur"` без інших) |
| `VerifyError` при відкритті lyrics | регістрова схема інша у цій збірці | надіслати `FATAL EXCEPTION` + post-patch smali |
| Розмиття немає, але логи є (`synced=false`) | unsynced текст або невірна семантика `p2` | перепатчити з вимкненою `Only for synced lyrics`; якщо не допомогло — надіслати logcat + як виглядають lyrics |
| Розмиття є скрізь (і активний) | переплутано active/synced або `lxs1.k` інверсований | надіслати logcat + smali `ksh0.a` (порядок операндів) |
| Мерехтіння/стрибки blur | двічі `move-result-object` або гонитва компоновки | відео + logcat + номер збірки |
| Не встановлюється | підпис (Play Store) | видалити оригінал; або `adb install-multiple` набором splits |

**Що надсилати в будь-якому FAIL:** точна версія+versionCode APKM; `result.json` (або повний лог Manager); `adb logcat` з фільтром вище; smali `ksh0.a` (до і після патчу); скріншот/відео, кроки відтворення.

## L8. Перед мерджем/релізом

- чекліст L2–L4 пройдений; `debugLogging` = дефолт `false`;
- PR за конвенціями апстріму (`dev` ← гілка, `feat(Spotify): …`), `CHANGELOG.md`/`patches-list.json` не редагувати;
- у PR — чесно: на яких збірках перевірено (version+code), що саме (L1–L4).
