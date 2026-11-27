package app.southstar21.patches.spotify.layout.lyricsblur

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The composable that draws one lyric line (obfuscated as p.ksh0.a in 9.2.4.2644). Spotify's
 * Compose code is minified, so the patch is tied to this one version.
 *
 * Parameters: line model, Z active, Z synced, F, I, Z, Modifier, Z, Z, Z, Boolean, Z, loc1,
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
