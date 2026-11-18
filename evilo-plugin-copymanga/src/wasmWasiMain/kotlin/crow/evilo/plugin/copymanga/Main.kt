package crow.evilo.plugin.copymanga

import crow.evilo.plugin.api.EviloPluginService
import crow.evilo.plugin.build.releaseDescriptor
import crow.wasmline.Wasmline
import crow.wasmline.bind

fun main() {
    Wasmline.get().bind(EviloPluginService::class, CopyMangaPlugin(releaseDescriptor))
}
