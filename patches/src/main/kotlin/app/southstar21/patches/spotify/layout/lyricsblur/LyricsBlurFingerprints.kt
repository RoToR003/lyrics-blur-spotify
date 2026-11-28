package app.southstar21.patches.spotify.layout.lyricsblur

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The composable that draws one lyric line (obfuscated as p.ksh0.a in 9.2.4.2644). Spotify's
 * Compose code is minified, so the patch is tied to this one version.
 *
 * Parameters: line model, Z (p2), Z (p3), F, I, Z, Modifier, Z, Z, Z, Boolean, Z, loc1,
 * Function1, Composer, I changed, I changed, I default.
 */
internal object LyricsLineFingerprint : Fingerprint(
    definingClass = "Lp/ksh0;",
    name = "a",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lp/psh0;", "Z", "Z", "F", "I", "Z", "Lp/xzo0;", "Z", "Z", "Z",
        "Ljava/lang/Boolean;", "Z", "Lp/loc1;", "Lp/en50;", "Lp/nvk;", "I", "I", "I",
    ),
)

/**
 * The translate-mode wrapper around a lyric line (obfuscated as p.ksh0.b in 9.2.4.2644):
 * wraps the plain line body in a crossfade between original and translated text. Also holds a
 * call of p.mtc1.f (its own line-width Modifier) and is used on the full-screen lyrics view.
 * Parameters: line model, Boolean?, Z (p3), F, Function0, Z (p6), Z (p7), Composer, I.
 *
 * Used only by this diagnostic build to find out which of the two line composables actually
 * renders the visible lyrics surface; the final fix will hook the one that draws.
 */
internal object LyricsLineWrapperFingerprint : Fingerprint(
    definingClass = "Lp/ksh0;",
    name = "b",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lp/psh0;", "Ljava/lang/Boolean;", "Z", "F",
        "Lkotlin/jvm/functions/Function0;", "Z", "Z", "Lp/nvk;", "I",
    ),
)

/**
 * The lambda that draws lyrics (and artist-event card) line text. Obfuscated as Lp.l8l in
 * 9.2.4.2644; renders via Lp.hps1;->c (the Text composable).
 *
 * A packed-switch on field "a" picks between two render paths: case 0 is the artist-events
 * full-bleed card (draws l8l fields f/g — a String and a z8l model); the fall-through branch
 * is the lyrics path (l8l field f is cast to Lp/psh0;, the lyric line model, and psh0.e is the
 * line text). Constructed in Lp.ksh0.c (lyrics lines, a=1) and Lp.v8l.f (events cards, a=0).
 *
 * Fields (byte "a" = lambda mode; Z "b" = synced / tap-to-seek; Z "c" = timing/layout
 * selector; float "d" = line scale; Z "e" = is this line the actively sung one).
 *
 * Parameters: Composer, changed (both boxed as java.lang.Object).
 */
internal object LyricsTextLineFingerprint : Fingerprint(
    definingClass = "Lp/l8l;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
)
