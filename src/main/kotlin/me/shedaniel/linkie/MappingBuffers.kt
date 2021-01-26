package me.shedaniel.linkie

import me.shedaniel.linkie.utils.StringPool
import okio.Buffer

fun ByteBuffer.writeMappingsContainer(mappingsContainer: MappingsContainer) {
    writeNotNullString(mappingsContainer.version)
    writeNotNullString(mappingsContainer.name)
    writeStringOrNull(mappingsContainer.mappingSource?.name)
    writeCollection(mappingsContainer.classes.values) { writeClass(it) }
}

fun ByteBuffer.writeClass(aClass: Class) {
    writeNotNullString(aClass.intermediaryName)
    writeMagicObf(aClass.intermediaryName, aClass.obfName)
    writeMagic(aClass.intermediaryName, aClass.mappedName)
    writeCollection(aClass.methods) { writeMethod(it) }
    writeCollection(aClass.fields) { writeField(it) }
}

fun ByteBuffer.writeMethod(method: Method) {
    writeNotNullString(method.intermediaryName)
    writeNotNullString(method.intermediaryDesc)
    writeMagicObf(method.intermediaryName, method.obfName)
    writeMagic(method.intermediaryName, method.mappedName)
}

fun ByteBuffer.writeField(field: Field) {
    writeNotNullString(field.intermediaryName)
    writeNotNullString(field.intermediaryDesc)
    writeMagicObf(field.intermediaryName, field.obfName)
    writeMagic(field.intermediaryName, field.mappedName)
}

fun ByteBuffer.writeMagic(original: String, string: String?) {
    when (string) {
        original -> writeByte(1)
        null -> writeByte(2)
        else -> {
            writeByte(3)
            writeNotNullString(string)
        }
    }
}

fun ByteBuffer.writeObf(obf: Obf) {
    when {
        obf.isEmpty() -> writeByte(0)
        obf.isMerged() -> when (obf.merged) {
            null -> writeByte(2)
            else -> {
                writeByte(3)
                writeNotNullString(obf.merged!!)
            }
        }
        else -> {
            writeByte(4)
            writeStringOrNull(obf.client)
            writeStringOrNull(obf.server)
        }
    }
}

fun ByteBuffer.writeMagicObf(original: String, obf: Obf) {
    when {
        obf.isEmpty() -> writeByte(0)
        obf.isMerged() -> writeMagic(original, obf.merged)
        else -> {
            writeByte(4)
            writeStringOrNull(obf.client)
            writeStringOrNull(obf.server)
        }
    }
}

fun ByteBuffer.readMappingsContainer(): MappingsContainer {
    val version = readNotNullString()
    val name = readNotNullString()
    val mappingSource = readStringOrNull()?.let { MappingsContainer.MappingSource.valueOf(it) }
    val mappingsContainer = MappingsContainer(version, name = name, mappingSource = mappingSource)
    readCollection { readClass() }.forEach {
        mappingsContainer.classes[it.intermediaryName] = it
    }
    return mappingsContainer
}

fun ByteBuffer.readClass(): Class {
    val intermediaryName = readNotNullString()
    val obfName = readMagicObf(intermediaryName)
    val mappedName = readMagic(intermediaryName)
    val aClass = Class(intermediaryName, obfName, mappedName)
    aClass.methods.addAll(readCollection { readMethod() })
    aClass.fields.addAll(readCollection { readField() })
    return aClass
}

fun ByteBuffer.readMethod(): Method {
    val intermediaryName = readNotNullString()
    val intermediaryDesc = readNotNullString()
    val obfName = readMagicObf(intermediaryName)
    val mappedName = readMagic(intermediaryName)
    return Method(intermediaryName, intermediaryDesc, obfName, mappedName)
}

fun ByteBuffer.readField(): Field {
    val intermediaryName = readNotNullString()
    val intermediaryDesc = readNotNullString()
    val obfName = readMagicObf(intermediaryName)
    val mappedName = readMagic(intermediaryName)
    return Field(intermediaryName, intermediaryDesc, obfName, mappedName)
}

fun ByteBuffer.readObf(): Obf {
    return when (readByte().toInt()) {
        0 -> Obf()
        2 -> Obf(merged = null)
        3 -> Obf(merged = readNotNullString())
        else -> {
            val client = readStringOrNull()
            val server = readStringOrNull()
            Obf(client, server)
        }
    }
}

fun ByteBuffer.readMagicObf(original: String): Obf {
    return when (readByte().toInt()) {
        0 -> Obf()
        1 -> Obf(merged = original)
        2 -> Obf(merged = null)
        3 -> Obf(merged = readNotNullString())
        else -> {
            val client = readStringOrNull()
            val server = readStringOrNull()
            Obf(client, server)
        }
    }
}

fun ByteBuffer.readMagic(original: String): String? {
    return when (readByte().toInt()) {
        1 -> original
        2 -> null
        else -> readNotNullString()
    }
}

fun writer(): ByteBuffer = ByteBuffer.writer()
fun reader(byteArray: ByteArray): ByteBuffer = ByteBuffer.reader(byteArray)

@JvmName("reader_")
fun ByteArray.reader(): ByteBuffer = reader(this)

@OptIn(ExperimentalUnsignedTypes::class)
@Suppress("unused")
class ByteBuffer(
    private val buffer: Buffer,
) {
    private val pool = StringPool()
    
    companion object {
        fun writer(): ByteBuffer = ByteBuffer(Buffer())
        fun reader(byteArray: ByteArray): ByteBuffer = ByteBuffer(Buffer()).apply { 
            writeByteArray(byteArray)
        }
    }

    fun writeByte(byte: Byte) = buffer.writeByte(byte.toInt())
    fun writeByteArray(array: ByteArray) = buffer.write(array)
    fun writeBoolean(boolean: Boolean) = buffer.writeByte(if (boolean) 1 else 0)
    fun writeShort(short: Short) = buffer.writeShort(short.toInt())
    fun writeUnsignedShort(short: UShort) = writeShort(short.toShort())
    fun writeInt(int: Int) = buffer.writeInt(int)
    fun writeLong(long: Long) = buffer.writeLong(long)
    fun writeFloat(float: Float) = writeInt(float.toBits())
    fun writeDouble(double: Double) = writeLong(double.toBits())
    fun writeChar(char: Char) = writeByte(char.toByte())

    inline fun <T> writeCollection(collection: Collection<T>, crossinline writer: ByteBuffer.(T) -> Unit) {
        writeInt(collection.size)
        collection.forEach { writer(this, it) }
    }

    fun writeTo(): ByteArray = buffer.inputStream().readBytes()

    fun readByte(): Byte = buffer.readByte()
    fun readByteArray(length: Int): ByteArray = buffer.readByteArray(length.toLong())
    fun readBoolean(): Boolean = buffer.readByte().toInt() == 1
    fun readShort(): Short = buffer.readShort()
    fun readUnsignedShort(): UShort = buffer.readShort().toUShort()
    fun readInt(): Int = buffer.readInt()
    fun readLong(): Long = buffer.readLong()
    fun readFloat(): Float = Float.fromBits(buffer.readInt())
    fun readDouble(): Double = Double.fromBits(buffer.readLong())
    fun readChar(): Char = buffer.readByte().toChar()

    inline fun <T> readCollection(crossinline reader: ByteBuffer.() -> T): List<T> {
        val size = readInt()
        val list = ArrayList<T>(size)
        for (i in 0 until size) {
            list.add(reader(this))
        }
        return list
    }

    fun writeStringOrNull(string: String?) {
        if (string != null) {
            writeNotNullString(string)
        } else {
            writeUnsignedShort(0U)
        }
    }

    fun writeNotNullString(string: String) {
        writeUnsignedShort((string.length + 1).toUShort())
        buffer.writeUtf8(string)
    }

    fun readStringOrNull(): String? {
        val length = readUnsignedShort().toLong()
        if (length == 0L) return null
        return pool[buffer.readUtf8(length - 1)]
    }

    fun readNotNullString(): String = readStringOrNull()!!
}
