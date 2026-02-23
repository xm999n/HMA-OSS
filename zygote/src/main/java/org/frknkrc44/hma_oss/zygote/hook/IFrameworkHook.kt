package org.frknkrc44.hma_oss.zygote.hook

interface IFrameworkHook {

    fun load()
    fun unload() {
        // TODO: Find a way to unload
    }
    fun onConfigChanged() {}
}
