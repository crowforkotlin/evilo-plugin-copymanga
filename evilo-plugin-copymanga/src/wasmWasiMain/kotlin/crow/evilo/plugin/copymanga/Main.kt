package crow.evilo.plugin.copymanga

import crow.evilo.plugin.api.EviloPluginService
import crow.evilo.plugin.api.MangaPluginAdapter
import crow.evilo.plugin.build.releaseDescriptor
import crow.wasmline.Wasmline
import crow.wasmline.bind

fun main() {
    val source = CopyMangaSource(releaseDescriptor.id, releaseDescriptor.version)
    Wasmline.get().bind(EviloPluginService::class, MangaPluginAdapter(source, releaseDescriptor))
}
