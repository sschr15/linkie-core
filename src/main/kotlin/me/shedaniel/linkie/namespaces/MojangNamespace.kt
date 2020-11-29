package me.shedaniel.linkie.namespaces

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.shedaniel.linkie.*
import me.shedaniel.linkie.MappingsContainerBuilder
import me.shedaniel.linkie.utils.onlyClass
import me.shedaniel.linkie.utils.toVersion
import me.shedaniel.linkie.utils.tryToVersion
import java.io.InputStream
import java.net.URL

object MojangNamespace : Namespace("mojang") {
    val versionJsonMap = mutableMapOf<String, String>()
    private var latestRelease = ""
    private var latestSnapshot = ""

    override fun getDependencies(): Set<Namespace> = setOf(YarnNamespace)

    init {
        fun getName(version: String): String = if (YarnNamespace.getProvider(version).isEmpty()) "Mojang" else "Mojang (via Intermediary)"

        registerSupplier(simpleCachedSupplier("1.14.4") {
            buildMappings(it, getName(it), expendIntermediaryToMapped = true) {
                readMojangMappings(
                    client = "https://launcher.mojang.com/v1/objects/c0c8ef5131b7beef2317e6ad80ebcd68c4fb60fa/client.txt",
                    server = "https://launcher.mojang.com/v1/objects/448ccb7b455f156bb5cb9cdadd7f96cd68134dbd/server.txt"
                )
                source(MappingsContainer.MappingSource.MOJANG)

                val yarn = YarnNamespace.getProvider(it)
                if (!yarn.isEmpty()) {
                    fill()
                    edit {
                        rewireIntermediaryFrom(yarn.mappingsContainer!!.invoke())
                    }
                    lockFill()
                }
            }
        })
        registerSupplier(multipleCachedSupplier({ versionJsonMap.keys }, {
            if (!YarnNamespace.getProvider(it).isEmpty()) "$it-intermediary" else it
        }) {
            buildMappings(it, getName(it), expendIntermediaryToMapped = true) {
                val url = URL(versionJsonMap[version])
                val versionJson = json.parseToJsonElement(url.readText()).jsonObject
                val downloads = versionJson["downloads"]!!.jsonObject
                readMojangMappings(
                    client = downloads["client_mappings"]!!.jsonObject["url"]!!.jsonPrimitive.content,
                    server = downloads["server_mappings"]!!.jsonObject["url"]!!.jsonPrimitive.content
                )
                source(MappingsContainer.MappingSource.MOJANG)

                val yarn = YarnNamespace.getProvider(it)
                if (!yarn.isEmpty()) {
                    fill()
                    edit {
                        rewireIntermediaryFrom(yarn.mappingsContainer!!.invoke())
                    }
                    lockFill()
                }
            }
        })
    }

    override fun supportsMixin(): Boolean = true
    override fun supportsAW(): Boolean = true

    override fun getDefaultLoadedVersions(): List<String> = listOf(latestRelease)

    override fun getAllVersions(): List<String> =
        versionJsonMap.keys.toMutableList().apply { add("1.14.4") }

    override fun reloadData() {
        versionJsonMap.clear()
        val versionManifest = json.parseToJsonElement(URL("https://launchermeta.mojang.com/mc/game/version_manifest.json").readText())
        val `19w36a` = "19w36a".toVersion()
        versionManifest.jsonObject["versions"]!!.jsonArray.forEach { versionElement ->
            val versionString = versionElement.jsonObject["id"]!!.jsonPrimitive.content
            val version = versionString.tryToVersion() ?: return@forEach
            if (version >= `19w36a`) {
                val urlString = versionElement.jsonObject["url"]!!.jsonPrimitive.content
                versionJsonMap[versionString] = urlString
            }
        }
        latestRelease = versionManifest.jsonObject["latest"]!!.jsonObject["release"]!!.jsonPrimitive.content
        latestSnapshot = versionManifest.jsonObject["latest"]!!.jsonObject["snapshot"]!!.jsonPrimitive.content
    }

    override fun getDefaultVersion(channel: () -> String): String = when (channel()) {
        "snapshot" -> latestSnapshot
        else -> latestRelease
    }

    override fun getAvailableMappingChannels(): List<String> = listOf("release", "snapshot")

    private fun MappingsContainerBuilder.readMojangMappings(client: String, server: String) {
        var clientBytes: ByteArray? = null
        var serverBytes: ByteArray? = null
        runBlocking {
            launch(Dispatchers.IO) {
                clientBytes = URL(client).readBytes()
            }
            launch(Dispatchers.IO) {
                serverBytes = URL(server).readBytes()
            }
        }
        readMappings(clientBytes!!.inputStream())
        readMappings(serverBytes!!.inputStream())
    }

    private fun MappingsContainerBuilder.readMappings(inputStream: InputStream) {
        fun String.toActualDescription(): String = when (this) {
            "boolean" -> "Z"
            "char" -> "C"
            "byte" -> "B"
            "short" -> "S"
            "int" -> "I"
            "float" -> "F"
            "long" -> "J"
            "double" -> "D"
            "void" -> "V"
            "" -> ""
            else -> "L${replace('.', '/')};"
        }

        fun getActualDescription(body: String, returnType: String): String {
            val splitClass = body.trimStart('(').trimEnd(')').split(',')
            return "(${splitClass.joinToString("") { it.toActualDescription() }})${returnType.toActualDescription()}"
        }

        var lastClass: ClassBuilder? = null
        inputStream.bufferedReader().forEachLine {
            if (it.startsWith('#')) return@forEachLine
            if (it.startsWith("    ")) {
                val s = it.trimIndent().split(':')
                if (s.size >= 3 && s[0].toIntOrNull() != null && s[1].toIntOrNull() != null) {
                    val split = s.drop(2).joinToString(":").split(' ').toMutableList()
                    split.remove("->")
                    lastClass!!.apply {
                        val methodName = split[1].substring(0, split[1].indexOf('('))
                        method(methodName, getActualDescription(split[1].substring(methodName.length), split[0])) {
                            obfMethod(split[2])
                        }
                    }
                } else {
                    val split = it.trimIndent().replace(" -> ", " ").split(' ')
                    lastClass!!.apply {
                        field(split[1], split[0].toActualDescription()) {
                            obfField(split[2])
                        }
                    }
                }
            } else {
                val split = it.trimIndent().trimEnd(':').split(" -> ")
                val className = split[0].replace('.', '/')
                val obf = split[1]
                if (className.onlyClass() != "package-info") {
                    lastClass = clazz(className, obf)
                }
            }
        }
    }
}