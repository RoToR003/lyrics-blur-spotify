package app.southstar21.patches.spotify.layout.lyricsblur

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.southstar21.patches.spotify.misc.extension.spotifySharedExtensionPatch
import app.southstar21.patches.spotify.shared.Constants.COMPATIBILITY_SPOTIFY
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.formatter.DexFormatter
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/southstar21/extension/spotify/lyricsblur/LyricsBlurPatch;"

/**
 * Spotify's own "blur this Modifier when the flag is true" (p.lxs1.k): a composed modifier that
 * animates a 12px blur in and out over 200 ms and does nothing below Android 12.
 */
private const val SPOTIFY_BLUR_MODIFIER = "Lp/lxs1;->k(Lp/xzo0;Z)Lp/xzo0;"

/** Inside the line composable: Modifier.fillMaxWidth(1f)-like call; v0 is the line's Modifier, v15 is 1.0f. */
private const val ANCHOR_CALL = "Lp/mtc1;->f(Lp/xzo0;F)Lp/xzo0;"

private fun extensionMethod(name: String, returnType: String) = Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = name,
    returnType = returnType,
    parameters = listOf(),
)

@Suppress("unused")
val spotifyLyricsBlurPatch = bytecodePatch(
    name = "Lyrics blur",
    description = "Blurs the lyrics lines that aren't being sung and keeps the current line sharp. " +
            "The blur fades in and out; it needs Android 12+.",
    default = true
) {
    compatibleWith(COMPATIBILITY_SPOTIFY)

    dependsOn(spotifySharedExtensionPatch)

    val requireSynced by booleanOption(
        key = "requireSynced",
        default = true,
        title = "Only for synced lyrics",
        description = "Blur only while the lyrics follow the song. Turn off if the blur never shows " +
                "on synced lyrics (then lyrics without timing are blurred as well).",
        required = true,
    )

    val debugLogging by booleanOption(
        key = "debugLogging",
        default = false,
        title = "Debug logging",
        description = "Writes what the hook sees to logcat under the tag LyricsBlur.",
        required = true,
    )

    execute {
        extensionMethod("requireSynced", "Z").method.addInstructions(
            0,
            """
                const/4 v0, ${if (requireSynced == true) 1 else 0}
                return v0
            """
        )
        extensionMethod("debugLogging", "Z").method.addInstructions(
            0,
            """
                const/4 v0, ${if (debugLogging == true) 1 else 0}
                return v0
            """
        )

        // Karaoke ("Sing") path. Optional: if the fingerprint fails, the regular-lyrics
        // hook (below) still applies, so don't abort the whole patch over it.
        val method = runCatching { LyricsLineFingerprint.method }.getOrNull()

        var singModeHooked = false
        if (method != null) {
        // Unblock the Compose changed-check in this composable. Spotify's lyrics list call
        // site passes the $changed/$default/$changed2 parameters as constant 0 ("nothing
        // changed"), so Compose always takes the "reuse the cached group" path and the
        // composable BODY never re-executes after the first composition: the blur injection
        // below (and Spotify's own modifier chain) would be dead code. The `if-eqz v2` that
        // guards the skip path (right after the p.sw50.Y call) is replaced by a nop; the
        // changed bits stay computed (v2 is unused afterwards) but no longer skip.
        // The body is small; the recomposition traffic observed on-device is ~16 Hz.
        // DexFormatter writes NO separator between parameter types: Y(IZ)Z, not Y(I Z)Z.
        // Guarded: if the call or the if-eqz is not where expected, the patch still applies and
        // logs one marker instead of aborting.
        val impl = method.implementation!!
        runCatching {
            val yIndex = impl.instructions.withIndex().firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)
                        ?.let { DexFormatter.INSTANCE.getMethodDescriptor(it) == "Lp/sw50;->Y(IZ)Z" } == true
            }?.index
                ?: error("p.sw50.Y call not found")
            val ifEqzIndex = (yIndex + 1 until minOf(impl.instructions.size, yIndex + 6))
                .firstOrNull { impl.instructions[it].opcode == Opcode.IF_EQZ }
                ?: error("changed-check if-eqz not found")
            // One instruction replaced by one instruction: code-unit layout shrinks by one unit
            // and branch targets are re-resolved by dexlib2 (builder labels), so everything else
            // stays valid.
            method.replaceInstructions(ifEqzIndex, "nop")
        }.onFailure {
            method.addInstructions(
                0,
                """
                    const-string v0, "LB: changed-check not found"
                    invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                """
            )
        }

        // The one call of ANCHOR_CALL in the method, with exactly (v0, v15) as arguments.
        // DexFormatter produces the same "Lclass;->name(params)return" string as the deprecated
        // ReferenceUtil.getMethodDescriptor, which this replaces. Recomputed here because the
        // diagnostic injections above shifted the instruction indices.
        // Non-fatal: this hook only covers the karaoke ("Sing") rendering path; the regular
        // lyrics lines go through Lp.l8l (see below), so failure here is not fatal.
        runCatching {
            val anchors = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)
                            ?.let { DexFormatter.INSTANCE.getMethodDescriptor(it) == ANCHOR_CALL } == true
            }
            check(anchors.size == 1) { "expected one call of $ANCHOR_CALL, found ${anchors.size}" }
            val (index, anchor) = anchors.single()
            val call = anchor as FiveRegisterInstruction
            check(call.registerC == 0 && call.registerD == 15) {
                "the call of $ANCHOR_CALL uses v${call.registerC}, v${call.registerD}, expected v0, v15"
            }

            // smali pN is 0-based: p0 = v62 = psh0 (reference), p1 = v63 (first Z), p2 = v64
            // (second Z). v63 is believed to be "this line is the active one"; v64 the
            // synced-mode flag.
            // v2 is free at the anchor: the next instruction after the anchor's result
            // overwrites it (`iget v2, ...`) before anything reads it.
            method.addInstructions(
                index,
                """
                    invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->shouldBlur(ZZ)Z
                    move-result v2
                    invoke-static { v0, v2 }, $SPOTIFY_BLUR_MODIFIER
                    move-result-object v0
                """
            )

            // Anchor tracker (diagnostic): logs (p1, p2) on change at the blur insertion
            // point. Sing-mode path only. Debug-only.
            method.addInstructions(
                index,
                """
                    invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->anchor(ZZ)V
                """
            )
            singModeHooked = true
        }.onFailure {
            method.addInstructions(
                0,
                """
                    const-string v0, "LB: sing-mode hook failed"
                    invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                """
            )
        }

        // Marker (a): at method entry — confirms this composable runs at all — and the entry
        // tracker: logs (p1, p2) once per second, so we see composition activity even if the
        // body exits before the anchor. { p1 .. p2 }: the two Z params (v63, v64). v0 is free on entry.
        method.addInstructions(
            0,
            """
                const-string v0, "LB: enter ksh0.a"
                invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->entry(ZZ)V
            """
        )

        }

        // The composable that draws every REGULAR lyric line's text — both the
        // original-line and the translation-line lambdas of Lp.l8l (a=0 / a=1),
        // constructed in Lp.ksh0.c and Lp.v8l.f. The hook above only covers karaoke
        // ("Sing") mode, so this is the primary hook.
        //
        // The method carries a packed-switch on field "a" (the lambda mode): case 0 is the
        // artist-events full-bleed card path (draws l8l fields f/g, a String and a z8l model),
        // while the fall-through branch is the lyrics path (check-casts l8l field f as
        // Lp/psh0; — the lyric line model — and draws psh0.e, guarded by
        // "#LyricsTranslationLineTag"). We must blur ONLY the lyrics path, so the hook is
        // injected right after the switch (start of the fall-through branch), not in the
        // shared prologue. v5 is field "e" (this line is the actively sung one — it gates the
        // "line scale" spring); v7 is field "b" (synced / tap-to-seek); v9 holds Lp/uzo0;->a
        // (the immutable Modifier constant); v12 is dead at this point (reassigned at the
        // changed-bit decode right below).
        var textLineHooked = false
        val textLine = runCatching { LyricsTextLineFingerprint.method }.getOrNull()
        if (textLine == null) {
            // Note the miss; if the sing-mode composable was found there is a place to put
            // the marker (its entry injection shifts indices but not this marker's position).
            runCatching {
                method?.addInstructions(
                    0,
                    """
                        const-string v0, "LB: l8l fingerprint not found"
                        invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                    """
                )
            }
        } else {
            val l8lImpl = textLine.implementation!!
            // Both branches (a=0 / a=1) carry their own Compose changed-check right after the
            // p.sw50.Y call; unblock both the same way so the body — and this blur — executes.
            // Non-fatal: the blur injection below is the essential part.
            runCatching {
                val yCalls = l8lImpl.instructions.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)
                            ?.let { DexFormatter.INSTANCE.getMethodDescriptor(it) == "Lp/sw50;->Y(IZ)Z" } == true
                }.map { it.index }
                for (yIndex in yCalls) {
                    val ifEqzIndex = (yIndex + 1 until minOf(l8lImpl.instructions.size, yIndex + 6))
                        .firstOrNull { l8lImpl.instructions[it].opcode == Opcode.IF_EQZ }
                        ?: continue
                    textLine.replaceInstructions(ifEqzIndex, "nop")
                }
            }
            val uzo0Index = l8lImpl.instructions.withIndex().firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.SGET_OBJECT &&
                    ((instruction as ReferenceInstruction).reference as? FieldReference)
                        ?.let { DexFormatter.INSTANCE.getFieldDescriptor(it) == "Lp/uzo0;->a Lp/uzo0;" } == true
            }?.index
            val switchIndex = l8lImpl.instructions.withIndex()
                .firstOrNull { (_, ins) -> ins.opcode == Opcode.PACKED_SWITCH }?.index
            when {
                uzo0Index == null -> runCatching {
                    textLine.addInstructions(
                        0,
                        """
                            const-string v0, "LB: l8l uzo0.a not found"
                            invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                        """
                    )
                }
                switchIndex == null -> runCatching {
                    textLine.addInstructions(
                        0,
                        """
                            const-string v0, "LB: l8l switch not found"
                            invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                        """
                    )
                }
                else -> {
                    textLine.addInstructions(
                        switchIndex + 1,
                        """
                            invoke-static { v5, v7 }, $EXTENSION_CLASS->anchorL(ZZ)V
                            invoke-static { v5, v7 }, $EXTENSION_CLASS->shouldBlur(ZZ)Z
                            move-result v12
                            invoke-static { v9, v12 }, $SPOTIFY_BLUR_MODIFIER
                            move-result-object v9
                        """
                    )
                    textLineHooked = true
                }
            }
        }

        if (!textLineHooked && !singModeHooked) {
            error("Lyrics blur: no hook could be injected (Lp.l8l and Lp.ksh0.a both failed); Spotify's code changed.")
        }

        // Diagnostic: probe the translate-mode wrapper composable p.ksh0.b — possibly the one
        // that actually renders the full-screen lyrics surface. Its own call of ANCHOR_CALL
        // (modifier + 1.0f, modifier in v1) is tracked with a per-call anchor tracker; no blur
        // is injected there yet: the (p5, p6) booleans' semantics are unknown. If the on-device
        // log shows bAnchor firing and the a-anchor silent, the blur hook belongs in b.
        // b params (0-based): p5 = v17 (Z), p6 = v18 (Z) -> { p5 .. p6 }.
        // Optional: if the wrapper's shape changes, the a-path stays patched.
        val wrapper = runCatching { LyricsLineWrapperFingerprint.method }.getOrNull()
        if (wrapper != null) {
            wrapper.addInstructions(
                0,
                """
                    const-string v0, "LB: enter ksh0.b"
                    invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                """
            )
            val bAnchors = wrapper.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)
                            ?.let { DexFormatter.INSTANCE.getMethodDescriptor(it) == ANCHOR_CALL } == true
            }
            if (bAnchors.size == 1) {
                val (bIndex, bAnchorInstruction) = bAnchors.single()
                val bCall = bAnchorInstruction as FiveRegisterInstruction
                if (bCall.registerC == 1 && bCall.registerD == 2) {
                    wrapper.addInstructions(
                        bIndex,
                        """
                            invoke-static/range { p5 .. p6 }, $EXTENSION_CLASS->anchorB(ZZ)V
                        """
                    )
                } else {
                    wrapper.addInstructions(
                        0,
                        """
                            const-string v0, "LB: b anchor regs != v1,v2 (see b smali)"
                            invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                        """
                    )
                }
            } else {
                wrapper.addInstructions(
                    0,
                    """
                        const-string v0, "LB: b anchors=${bAnchors.size} (see b smali)"
                        invoke-static { v0 }, $EXTENSION_CLASS->marker(Ljava/lang/String;)V
                    """
                )
            }
        }
    }
}
