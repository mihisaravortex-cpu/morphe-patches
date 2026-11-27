package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.interaction.downloads.ResolveCommandFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/smartdownloads/SmartDownloadsPatch;"
private const val MENU_ITEM_CLASS =
    "Lapp/morphe/extension/music/patches/smartdownloads/SmartCacheMenuItem;"
private const val DISPATCHER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/smartdownloads/SmartDownloadsPatch$CommandDispatcher;"
private const val OFFLINE_HANDLER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/smartdownloads/SmartDownloadsPatch$OfflineHandler;"
private const val GET_GENERATED_REGISTRY =
    "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"

private fun protoDefaultInstance(messageType: String) =
    protoDefaultInstanceFingerprint(messageType).instructionMatches.first().fieldReference

private val smartDownloadsResourcePatch = patch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_smart_downloads_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_smart_downloads_enabled"),
                    ListPreference("morphe_smart_downloads_play_threshold"),
                    ListPreference(
                        key = "morphe_smart_downloads_max_songs",
                        tag = "app.morphe.extension.music.settings.preference.MaxSmartDownloadsPreference"
                    ),
                    ListPreference("morphe_smart_downloads_inactivity_days"),
                    ListPreference("morphe_smart_downloads_repersonalize_days"),
                    SwitchPreference("morphe_smart_downloads_wifi_only"),
                    SwitchPreference("morphe_smart_downloads_like_boost"),
                    SwitchPreference("morphe_smart_downloads_battery_guard"),
                    TextPreference(
                        "morphe_smart_downloads_status",
                        tag = "app.morphe.extension.music.settings.preference.SmartCacheStatusPreference"
                    ),
                    TextPreference(
                        "morphe_smart_downloads_repersonalize_now",
                        tag = "app.morphe.extension.music.settings.preference.SmartCacheClearPreference"
                    ),
                )
            )
        )
    }
}

val smartDownloadsPatch = patch(
    name = "Smart Downloads",
    description = "Automatically caches frequently played songs offline with LRU eviction, pinning, and song-by-song management.",
) {
    dependsOn(
        smartDownloadsResourcePatch,
        downloadsPatch,
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        musicVideoTimeHook(EXTENSION_CLASS, "onVideoTimeUpdate")

        val commandType = ResolveCommandFingerprint.method.parameterTypes.first().toString()

        ResolveCommandFingerprint.let { fp ->
            val defaultInstanceField = protoDefaultInstance(commandType)

            val invokeCmd = if (fp.method.parameterTypes.size >= 2) {
                """
                    invoke-static { }, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
                    move-result-object v1
                    invoke-virtual { p0, v0, v1 }, ${fp.method}
                """
            } else {
                "invoke-virtual { p0, v0 }, ${fp.method}"
            }

            fp.classDef.addPatchInterface(
                DISPATCHER_INTERFACE,
                "patch_resolveCommand",
                """
                    sget-object v0, $defaultInstanceField
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v1
                    invoke-static { v0, p1, v1 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    check-cast v0, $commandType
                    $invokeCmd
                    return-void
                """
            )

            fp.method.addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->setCommandDispatcher($DISPATCHER_INTERFACE)V"
            )
        }

        // Direct OfflineVideoEndpoint handler hook (bypasses CommandResolver routing issues).
        MusicOfflineVideoEndpointFingerprint.let { offlineFp ->
            val offlineParamType = offlineFp.method.parameterTypes.first().toString()
            val bytesReg = if (offlineParamType == commandType) "p1" else "p2"

            offlineFp.classDef.addPatchInterface(
                OFFLINE_HANDLER_INTERFACE,
                "patch_handleOffline",
                """
                    sget-object v0, ${protoDefaultInstance(offlineParamType)}
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v1
                    invoke-static { v0, $bytesReg, v1 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    check-cast v0, $offlineParamType
                    invoke-static { }, Ljava/util/Collections;->emptyMap()Ljava/util/Map;
                    move-result-object v1
                    invoke-virtual { p0, v0, v1 }, ${offlineFp.method}
                    return-void
                """
            )

            val initMethod = offlineFp.classDef.methods.first { it.name == "<init>" }
            val initIndex = initMethod.indexOfFirstInstructionOrThrow(
                methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>")
            ) + 1
            initMethod.addInstruction(
                initIndex,
                "invoke-static { p0 }, $EXTENSION_CLASS->setOfflineHandler($OFFLINE_HANDLER_INTERFACE)V"
            )
            offlineFp.method.addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->setOfflineHandler($OFFLINE_HANDLER_INTERFACE)V"
            )
        }

        // Inject "Pin to Smart Cache" / "Unpin" into YouTube Music's 3-dot song menu.
        MusicMenuBottomSheetItemsFingerprint.let {
            val menuField = it.instructionMatches.first().fieldReference
            val menuItemType = it.instructionMatches[3].instruction.getReference<TypeReference>()!!.type
            val addItemMethod = it.instructionMatches.last().methodReference
            val itemsType = addItemMethod.definingClass

            val addSmartMenuItemMethod = ImmutableMethod(
                it.classDef.type,
                "patch_addSmartMenuItems",
                listOf(
                    ImmutableMethodParameter(itemsType, null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null)
                ),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p1 }, $MENU_ITEM_CLASS->getSmartMenuItems(Ljava/lang/Object;)[[B
                        move-result-object v0
                        array-length v1, v0
                        const/4 v2, 0x0
                        sget-object v3, ${protoDefaultInstance(menuItemType)}
                        :next_smart_item
                        if-ge v2, v1, :no_more_smart_items
                        aget-object v4, v0, v2
                        invoke-static { v3, v4 }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v4
                        invoke-virtual { p0, v4 }, $addItemMethod
                        add-int/lit8 v2, v2, 0x1
                        goto :next_smart_item
                        :no_more_smart_items
                        return-void
                    """
                )
            }
            it.classDef.methods.add(addSmartMenuItemMethod)

            it.method.cloneParameters().apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val itemsRegister = getInstruction<OneRegisterInstruction>(index).registerA
                    val menuRegister = findFreeRegister(index, itemsRegister)

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            iget-object v$menuRegister, p0, $menuField
                            invoke-static { v$itemsRegister, v$menuRegister }, $addSmartMenuItemMethod
                        """
                    )
                }
            }
        }

        MenuItemClickFingerprint.let {
            val menuItemField = it.instructionMatches.first().fieldReference
            it.method.apply {
                val register = findFreeRegister(0)
                addInstructions(
                    0,
                    """
                        iget-object v$register, p0, $menuItemField
                        invoke-static { v$register }, $MENU_ITEM_CLASS->onMenuItemClick(Ljava/lang/Object;)V
                    """
                )
            }
        }
    }
}
