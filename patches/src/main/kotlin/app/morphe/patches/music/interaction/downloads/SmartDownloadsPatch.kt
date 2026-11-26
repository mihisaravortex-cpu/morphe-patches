package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.interaction.downloads.ResolveCommandFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/smartdownloads/SmartDownloadsPatch;"
private const val DISPATCHER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/smartdownloads/SmartDownloadsPatch$CommandDispatcher;"
private const val GET_GENERATED_REGISTRY =
    "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"

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
                    TextPreference("morphe_smart_downloads_status", tag = "android.preference.Preference"),
                    TextPreference("morphe_smart_downloads_repersonalize_now", tag = "android.preference.Preference"),
                )
            )
        )
    }
}

val smartDownloadsPatch = patch(
    name = "Smart Downloads",
    description = "Automatically caches frequently played songs offline with LRU eviction and capacity limits.",
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
        // 1. Hook playback time ticks (~1000ms) for 65% valid play detection.
        musicVideoTimeHook(EXTENSION_CLASS, "onVideoTimeUpdate")

        // 2. Expose CommandResolver to SmartDownloadsPatch for background OfflineCommand dispatch.
        ResolveCommandFingerprint.let { fp ->
            val commandType = fp.method.parameterTypes.first().toString()
            val defaultInstanceField = protoDefaultInstanceFingerprint(commandType)
                .instructionMatches.first().fieldReference

            val invokeCmd = if (fp.method.parameterTypes.size >= 2) {
                """
                    const/4 v1, 0x0
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
    }
}
