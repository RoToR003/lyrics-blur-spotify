# Lyrics blur для Spotify 9.2.4.2644 — дослідницькі нотатки

> Виправлена та доповнена версія початкових нотаток. Що саме змінено — розділи 4–7;
> що перевірити не вдалось — розділ 8. Мова оригіналу (українська) збережена.
> Дата перевірки: 2026-11-27.

## 1. Фаза 2 — як це знайдено (і як перевірити в JADX-GUI)

**Стек UI.** У 9.2.4.2644 екран lyrics — **Jetpack Compose**, обфусковано в пакет `p.*` (View/RecyclerView-шлях не використовується).

**Пошук у JADX-GUI** (`Navigation → Text search`, `Ctrl+Shift+F`, режим «Code», відключити «Class/Method» щоб шукати саме літерали):

| Що шукати | Де потрапите | Навіщо |
|---|---|---|
| `lyrics line scale` | `p.ksh0.a(...)` | Composable одного рядка; це ціль патча |
| `LyricsLineActive` | `p.ksh0.<clinit>` | підтвердження, що це саме рядок lyrics |
| `blurRadius` | `p.ix70.invoke` (case `a=1`) | готовий animated blur (12 px, 200 мс, API ≥ 31) |
| `Landroid/graphics/RenderEffect;->createBlurEffect` | `p.wsg0`, `p.ci4` | реалізація blur |

Щоб побачити smali: у вкладці класу натисніть **Smali** (перемикач «Code / Smali» внизу). Якщо JADX не декомпілює Compose-метод (`Method not decompiled`) — це нормально, ціла робота йде зі smali.

**Як зрозуміти регістри.**
1. Читаєте `.registers N` та кількість параметрів `ins` (для `static` — всі параметри, для instance + `this`).
2. Параметри займають **останні `ins` регістрів**: `p0` = `v(N − ins)`. Для `ksh0.a`: `.registers 80`, `ins=18` ⇒ `p0 = v62 … p17 = v79`.
3. Приклад із `ksh0.a(psh0 p0, Z p1, Z p2, F p3, I p4, Z p5, Modifier p6, …, Composer p14, I p15, I p16, I p17)`.
4. `invoke-static` кодує лише `v0…v15`; для `p1`,`p2` (= v63, v64) обов'язково `invoke-static/range {p1 .. p2}`.
5. Регістр можна використати як тимчасовий, якщо після точки вставки його **записують до першого читання** (у нас `v2`: наступна інструкція після якоря — `iget v2, …psh0->a`).

**Семантика, виведена з smali:**
- `p1` = рядок активний (`sit1.v(mzh0, index)`: true лише для активного індексу).
- `p2` = режим синхронізації (`tzh0.b`).
- `p6` = Modifier рядка; у методі він лежить у `v0` до виклику `mtc1.f(v0, 1.0f)`.
- `lxs1.k(Modifier, Z)` = `composed(modifier, ix70(1, flag))` — animated blur (API ≥ 31 перевіряється всередині).

**Для View-стеку (інші версії Spotify; тут не використовується):** `LyricsBlurPatch.setViewBlur(view, blur)` в extension — плавний `View.setRenderEffect(createBlurEffect(...))` через `ValueAnimator`, `null` для різкого стану, перевірка API 31.

## 2. Архітектура

```
ksh0.a(...)  ── (bytecode patch) ──►  v0 = lxs1.k(v0, LyricsBlurPatch.shouldBlur(p1 /*active*/, p2 /*synced*/))
                                         │                         │
                                         │                         └─ extension: SDK>=31, ENABLED, REQUIRE_SYNCED, !active
                                         └─ Spotify: tween 200мс 0↔12px, graphicsLayer(RenderEffect.blur), skip якщо ≤0 або API<31
```

- Один патчований метод покриває і Now Playing, і fullscreen (`ksh0.a` викликають `f0o#h`/`kk1#I`; `dsh0` — службова restart-lambda, її не чіпаємо).
- Fingerprint — за `definingClass`/ім'ям/сигнатурою; `p.*` обфусковані, тому патч прив'язаний до 9.2.4.2644; якір вставки (`mtc1.f`) перевіряється на кількість (1) і регістри (v0, v15), інакше патч падає з зрозумілим повідомленням, а не створює битий dex.
- Вставка відбувається **перед** якорем (`addInstructions(index, …)`), тож `v0` вже є розмитим Modifier'ом, коли якір (`mtc1.f(v0, 1.0f)`, fillMaxWidth) його споживає.
- `p1..p2` (v63..v64) розв'язує `InlineSmaliCompiler` **справжнім smali-парсером** (ANTLR) з урахуванням `registerCount` і параметрів цільового методу — не регулярками.

## 3. Файли для інтеграції в репозиторій southstar21 (`southstar21dev/southstar21-patches`)

Код написаний під стиль репо (Fingerprint-об'єкти з `definingClass`, `extensionMethod(...)` + `addInstructions`, `dependsOn(spotifySharedExtensionPatch)`, `COMPATIBILITY_SPOTIFY`):

```
extensions/spotify/src/main/java/app/southstar21/extension/spotify/lyricsblur/LyricsBlurPatch.java
patches/src/main/kotlin/app/southstar21/patches/spotify/layout/lyricsblur/LyricsBlurFingerprints.kt
patches/src/main/kotlin/app/southstar21/patches/spotify/layout/lyricsblur/LyricsBlurPatch.kt
```

Додавати щось у `SpotifyHooks` не треба: патч не потребує хука `onWindowFocusChanged`, extension викликається прямо зі smali. `isIncluded()` теж не потрібен — extension не реєструє колбеків, його методи викликаються лише з запатченого байткоду і самі собою нічого не роблять, доки туди не дотягнеться smali.

**Опції патча** (як у Liquid Glass, значення «запікаються» у методи extension): `Only for synced lyrics` (`requireSynced`, за замовчуванням увімкнено) і `Debug logging` (`debugLogging`, за замовчуванням вимкнено — для першого тесту ввімкніть).

**Одне, що треба зробити вручну:** додати `AppTarget("9.2.4.2644")` у `COMPATIBILITY_SPOTIFY` — див. розділ 4 і `docs/integration.md`.

## 4. Виправлення: COMPATIBILITY_SPOTIFY (була неточність у нотатках)

У початкових нотатках було: «Якщо там лише 9.2.0.2053, Manager не запропонує патч для 9.2.4.2644».

Фактичний стан upstream (`patches/src/main/kotlin/app/southstar21/patches/spotify/shared/Constants.kt`, станом на `main` @ `30af566`, v1.4.0):

```kotlin
targets = listOf(
    AppTarget(version = "9.2.0.2053"),          // єдиний перевірений
    AppTarget(version = null, isExperimental = true),  // catch-all для всіх решти
)
```

Тобто Manager **запропонує** bundle і для 9.2.4.2644 — як експериментальний (catch-all). Додавати 9.2.4.2644 все одно треба, але не щоб «дозволити», а щоб позначити версію **перевіреною** (без experimental-мітки). Це один рядок у `Constants.kt` + оновлення бейджа/README upstream (опціонально).

## 5. Що саме перевірено, і проти яких джерел

Усі перевірки нижче — read-only аудит справжніх джерел, без збірки. Збірку (Gradle/Android SDK) у цьому середовищі виконати не можна — див. розділ 8.

| # | Що перевіряв | Джерело (піна upstream) | Результат |
|---|---|---|---|
| 1 | `bytecodePatch(name, description, default) { … }`, `compatibleWith`, `dependsOn` | `MorpheApp/morphe-patcher` tag `v2.4.0` (`0aed87c`) — `patches/build.gradle.kts` → `libs.versions.toml: morphe-patcher = "2.4.0"` | ✅ є, сигнатури збігаються |
| 2 | `booleanOption(key, default, title, description, required)` | те саме, `patch/Option.kt:312` | ✅ |
| 3 | `Fingerprint(definingClass=…, name=…, accessFlags=…, returnType=…, parameters=…)` | те саме, `Fingerprint.kt` | ✅ named-параметри існують |
| 4 | `Fingerprint.method` → `match().method`, кидає `PatchException` якщо не знайдено | те саме, `Fingerprint.kt:2484` | ✅ |
| 5 | `InstructionExtensions.addInstructions(index, smaliInstructions: String)` для `MutableMethod` | те саме, `InstructionExtensions.kt:143` | ✅ |
| 6 | `InlineSmaliCompiler`: справжній парсер smali (ANTLR, `smaliParser`), `pN` розв'язуються за `registerCount` і параметрами цільового методу; при вставці — цільовим є `ksh0.a` (80 регистрів, 18 ins ⇒ `p1=v63`, `p2=v64`; `invoke-static/range {p1 .. p2}` — єдиний легальний варіант, бо `p1`,`p2` вне `v0..v15`) | те саме, `util/smali/InlineSmaliCompiler.kt:81-88`, `Patch` вставка через `toInstructions(this)` | ✅ логіка коректна |
| 7 | `Opcode.INVOKE_STATIC` (0x71, Format.35c), Format35c = `FiveRegisterInstruction` + `ReferenceInstruction`; `getRegisterC()/getRegisterD()` = перший/другий аргументи (low/high nibble 4-го байта) — перевірка `registerC == 0 && registerD == 15` правильна | `MorpheApp/smali` tag `pin/4220ae75f7` (`4220ae7`, jitpack-піна в `libs.versions.toml: smali = "4220ae75f7"`) | ✅ |
| 8 | `DexFormatter.INSTANCE.getMethodDescriptor(ref)` повертає `"Lclass;->name(params)return"` — байт у байт те саме, що deprecated `ReferenceUtil.getMethodDescriptor`; `ReferenceUtil` у цій зміні **замінено** (див. розділ 6) | те саме, `formatter/DexFormatter.java`, `formatter/DexFormattedWriter.java:81-91` | ✅ |
| 9 | `accessFlags` у Fingerprint порівнюються **усіма бітами** (`accessFlagsMask = -1`, `matchMethodOrNull`: `(method.accessFlags and mask) != value → null`) | `morphe-patcher v2.4.0`, `Fingerprint.kt:1181-1186, 1622` | ⚠️ див. розділ 7 |
| 10 | Extension збирається з `compileSdk = 36`, `minSdk = 23`; deprecated/NewApi-патерн `@SuppressLint("NewApi")` + перевірка `SDK_INT >= S` — той самий, що в upstream `EdgeBlurView.java` | `MorpheApp/morphe-patches-gradle-plugin` main @ `855f27f`, `ExtensionPlugin.kt:57-60` | ✅ `VERSION_CODES.S`, `android.graphics.RenderEffect` доступні |
| 11 | Той самий `matchMethodOrNull` перевірено на main (v2.6.0, `0ddca86`) — API не змінилося | idem | ✅ |
| 12 | Код/наявність імен: `spotifyLyricsBlurPatch` у upstream не існує; issue/PR «lyrics»/«blur» в `southstar21-patches` — жодного; prior art є лише для YouTube Music (`MorpheApp/morphe-patches`, `extensions/music/.../lyrics/*`) — Spotify там немає | GitHub search, 2026-11-27 | ✅ колізій немає |
| 13 | `patches-list.json` і `CHANGELOG.md` в upstream — **автогенеровані** (semantic-release, «Do NOT manually edit»); комміти: `feat(Spotify): …`, PR у `dev` | `southstar21-patches`, `patches-list.json` (NOTE), `git log` | ✅ не редагувати руками |
| 14 | Синтаксис Kotlin обох файлів | `kotlin-parser` 0.0.12 (WASM), `KT_FILE` tree, 0 error nodes | ✅ |
| 15 | Синтаксис Java-елементу | `java-parser` 3.0.1 (chevrotain, граматика Java) | ✅ |

## 6. Рев'ю коду — що знайдено і що виправлено

| Знахідка | Рішення |
|---|---|
| `ReferenceUtil.getMethodDescriptor` — `@Deprecated` на класі («use DexFormatter instead»), ніхто в upstream його не використовує | **Виправлено:** `DexFormatter.INSTANCE.getMethodDescriptor(...)` (поведінка ідентична, див. п.8 розділу 5). Зміна локальна в `LyricsBlurPatch.kt` |
| Fingerprint порівнює всі біти `accessFlags` (`mask = -1`) | Не баг, але тендітно: якщо в зібраному `ksh0.a` з'явиться `ACC_SYNTHETIC` (0x1000) — fingerprint не зматчиться і патч впаде з `PatchException` **до** запису APK. Альтернатива — `isStatic = true` (порівнюється лише STATIC-біт), але тоді слабша перевірка. Залишено як є (автор бачив саме `public static final` у smali); додано попередження тут |
| `const/4 v0, 1/0` у перезаписаних `requireSynced()`/`debugLogging()`: оригінальне тіло методу стає недосяжним кодом | Ок: саме так робить upstream (Liquid Glass); D8/dex2dex недостижимий код витримують |
| `shouldBlur` ловить `Throwable` → `false` | Ок, ніколи не валить застосунок; `Log.e` з тегом `LyricsBlur` |
| `viewStates` (WeakHashMap) — лише для View-стеку, Compose-патчем не викликається | Ок, свідомо залишено як довідковий шлях |
| `calls++` рахує лише при `debugLogging() == true` | Ок, у релізі нуль накладних витрат |
| Extension-методи `requireSynced`/`debugLogging` залежать від того, що smali-вставка `const/4 v0, X; return v0` перезаписує їх раніше зачитання | Ок, `addInstructions(0, …)` вставляє на початок |
| `"Lp/lxs1;->k(Lp/xzo0;Z)Lp/xzo0;"` і `"Lp/mtc1;->f(Lp/xzo0;F)Lp/xzo0;"` — рядки класів `p.*` | Нормально для цього APK; при зміні збірки — шукати заново (розділ 10) |
| Extension → smali: `shouldBlur(ZZ)Z` ↔ `public static boolean shouldBlur(boolean, boolean)` | ✅ дескриптори збігаються (`Z` = boolean) |
| Порядок операндів `invoke-static/range {p1 .. p2}` → `shouldBlur(p1, p2)` = `shouldBlur(active, synced)` | ✅ відповідає сигнатурі `shouldBlur(boolean active, boolean synced)` |

## 7. Деталі збігів — нотатки рев'юверу

- **Вставка перед якорем.** `addInstructions(index, …)` вставляє інструкції **до** інструкції `index`. Якір `mtc1.f(v0, 1.0f)` споживає `v0` вже **після** нашого `move-result-object v0` — саме так `v0` стає «Modifier з розмиттям» ще до `fillMaxWidth`. Це і є весь трюк патчу.
- **Чому `v2`.** Перша інструкція після якоря — `iget v2, …` (перевзаписує `v2` до першого читання); наші `move-result v2` → `invoke-static {v0, v2}` читають `v2` до того, тож безпечно.
- **`p1..p2` у `/range`.** `p0=v62…p17=v79`; звичайний `invoke-static {p1 .. p2}` неможливий — `p1`,`p2` > `v15`. `invoke-static/range { p1 .. p2 }` — правильна форма (Format3rc).
- **`move-result-object v0`**, не `move-result` — `lxs1.k` повертає `Modifier` (об'єкт). ✅
- **`INVOKE_STATIC` vs `INVOKE_STATIC_RANGE`.** Якір шукається як `Opcode.INVOKE_STATIC` (35c). Якщо в майбутній збірці `mtc1.f(…)` викликатиметься через `/range`, якір не знайдеться → патч впаде з `expected one call …, found 0` — це **безпечна** поведінка (помилка, не битий APK).
- **`accessFlags` exact-compare** — див. розділ 6.

## 8. Що НЕ перевірено (потрібні APK та/або пристрій)

1. **Smali-якорі.** `p.ksh0.a`, `p.lxs1.k`, `p.mtc1.f`, `p.ix70`, `p.en50/p.qp61/p.zm51` (для варіанту з власним радіусом), регістри `v0/v15`, `.registers 80`, `ins=18`, `p1=v63`, `p2=v64`. Усе це — з аналізу автора в JADX; у середовищі без APK 9.2.4.2644 перевірити не можна (APKMirror з цього середовища недоступна; Play Store APK не містить `apkm`-розкладки). **Якщо хоч щось із цього не так — дивіться чекліст, п.9.**
2. **Повна збірка.** `kotlinc`/JDK і Android SDK у середовищі немає; gradle-плагіни — через `gradlePluginPortal()`/`google()`/`jitpack`, які звідси не дістаються. Тому Kotlin компілювався лише синтаксично (WASM-парсер), а API — лише аудитом вихідників пінів upstream (розділ 5). **Перший `./gradlew build` треба зробити на машині з SDK** — очікуємо щонайбільше помилки в імпортах (DexFormatter вже перевірено проти піна `4220ae75f7`).
3. **Поведінка на пристрої.** Розмиття, tween 200 мс, logcat, Now Playing та fullscreen, планшети/складні. Тільки руки + adb.
4. **Android 12/13/17** — blur вмикається тільки на API ≥ 31; на Android 17 (API 37) `RenderEffect` стабільний.

## 9. Чекліст

**Збірка** (на машині з JDK 17+ і Android SDK):
```
./gradlew build            # перевірка Kotlin + Java
./gradlew buildAndroid     # якщо шаблон Morphe використовує цей таск для .mpp
```
Очікуємо `BUILD SUCCESSFUL` і `.mpp` у `patches/build/libs/`.

**Встановлення**
1. Morphe Manager → додайте локальний `.mpp` (або ваш bundle).
2. Виберіть Spotify **9.2.4.2644** (`.apkm`) → увімкніть **Lyrics blur** → Patch → Install.
3. Видаліть/відв'яжіть оригінальний Spotify, якщо підпис інший.

**Дебаг (`adb logcat`)**
```
adb logcat -c
adb logcat -s LyricsBlur:D AndroidRuntime:E *:S
# або ширше:
adb logcat | grep -iE "LyricsBlur|FATAL EXCEPTION|VerifyError|InstantiationError"
```
Відкрийте lyrics (fullscreen і Now Playing), дайте пісні заграти. Очікуєте рядки на кшталт:
```
D LyricsBlur: line hook: active=false synced=true (calls=…)
D LyricsBlur: line hook: active=true  synced=true (calls=…)
```
- Є обидві комбінації, `synced=true` → працює. Неактивні розмиті, активний різкий.
- `synced=false` завжди (а lyrics синхронізовані) → вимкніть опцію `Only for synced lyrics` у Manager і запатчіть знову (але тоді несинхронізовані lyrics теж розмиються; для чистого рішення — напишіть мені лог).
- Нічого не логується → вимкнена опція `Debug logging`, або хук не виконується (інша версія Spotify).
- `VerifyError` / крах при відкритті lyrics → якір/регістри не ті; пришліть повний `FATAL EXCEPTION` і smali `ksh0.a` після патча.
- Fingerprint не зматчився → `PatchException: …ksh0…` до запису APK: пришліть `.access flags` і сигнатуру методу з smali цієї збірки.
- Android < 12 → blur не вмикається (за дизайном).

**Для релізу:** вимкніть опцію `Debug logging`. Патч у production upstream йде з `default = true`, `debugLogging` дефолтом `false` — готово з коробки.

## 10. Обмеження варіанту

- Радіус фіксований (12 px, зашито у `ix70`), тривалість 200 мс. Свій радіус/криву — окремий варіант зі своєю graphicsLayer-lambda (потрібні stub-класи `p.en50/p.qp61/p.zm51/p.u75/p.zgf1`).
- У станах «до початку пісні» й «після кінця» активного рядка немає — при `synced=true` усі рядки будуть розмиті.
- Патч прив'язаний до 9.2.4.2644: на інших збірках змінюються імена `p.*` (`lxs1`, `mtc1`), імена `p.ksh0`, `p.lxs1`, `p.mtc1` зміняться — треба знайти їх заново (рядки `lyrics line scale` у JADX).
- Перевірка `.access flags`: якщо після оновлення Spotify fingerprint впаде з «не знайдено метод» — звірте біти доступу (`public static final` і тільки вони).

## 11. Сусідні проєкти

- [southstar21dev/southstar21-patches](https://github.com/southstar21dev/southstar21-patches) — цільовий репозиторій інтеграції (v1.4.0 на час аудиту). Лірки там немає.
- [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) — офіційні patches: lyrics-патчі лише для **YouTube Music** (`extensions/music/.../lyrics/*`), Spotify відсутній.
- Spicetify-косметика (десктоп) — інша платформа, не перетинається.
