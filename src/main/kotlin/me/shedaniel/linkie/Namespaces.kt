package me.shedaniel.linkie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.launch
import me.shedaniel.linkie.utils.debug
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object Namespaces {
    lateinit var config: LinkieConfig
    val namespaces = mutableMapOf<String, Namespace>()
    val cachedMappings = CopyOnWriteArrayList<MappingsContainer>()
    val cacheFolder: File
        get() = config.cacheDirectory

    private fun registerNamespace(namespace: Namespace) = namespace.also {
        namespaces[it.id] = it
    }

    operator fun get(id: String) = namespaces[id]!!

    fun getMaximumCachedVersion(): Int = config.maximumLoadedVersions

    fun limitCachedData() {
        val list = mutableListOf<String>()
        while (cachedMappings.size > getMaximumCachedVersion()) {
            val first = cachedMappings.first()
            cachedMappings.remove(first)
            list.add(first.let { "${it.namespace}-${it.version}" })
        }
        System.gc()
        debug("Removed ${list.size} Mapping(s): " + list.joinToString(", "))
    }

    fun addMappingsContainer(mappingsContainer: MappingsContainer) {
        cachedMappings.add(mappingsContainer)
        limitCachedData()
        debug("Currently Loaded ${cachedMappings.size} Mapping(s): " + cachedMappings.joinToString(", ") {
            "${it.namespace}-${it.version}"
        })
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    fun init(
        config: LinkieConfig,
    ) {
        Namespaces.config = config
        config.namespaces.forEach {
            registerNamespace(it)
            it.getDependencies().forEach { dependency -> registerNamespace(dependency) }
        }
        val tickerChannel = ticker(delayMillis = config.reloadCycleDuration.toMillis(), initialDelayMillis = 0)
        CoroutineScope(Dispatchers.Default).launch {
            for (event in tickerChannel) {
                cachedMappings.clear()
                namespaces.map { (_, namespace) ->
                    launch {
                        namespace.reset()
                    }
                }.forEach { it.join() }
                System.gc()
            }
        }
    }
}