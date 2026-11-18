@file:OptIn(ExperimentalSerializationApi::class)

package crow.evilo.plugin.copymanga

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal val CopyMangaJson = Json { ignoreUnknownKeys = true }

/** Some routes put an object or array inside a JSON string. Decode that string once. */
internal fun Json.unpack(element: JsonElement): JsonElement =
    if (element is JsonPrimitive && element.isString) parseToJsonElement(element.content) else element

/** Accepts both an array and a string containing that array, without dropping invalid entries. */
internal class LenientList<T>(elementSerializer: KSerializer<T>) : KSerializer<List<T>?> {
    private val delegate = ListSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor.nullable

    override fun deserialize(decoder: Decoder): List<T>? {
        val json = decoder as? JsonDecoder ?: throw SerializationException("CopyManga lists require JSON.")
        val element = json.json.unpack(json.decodeJsonElement())
        if (element is JsonNull) return null
        if (element !is JsonArray) throw SerializationException("Expected a CopyManga array.")
        return json.json.decodeFromJsonElement(delegate, element)
    }

    override fun serialize(encoder: Encoder, value: List<T>?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(delegate, value)
    }
}

internal object LenientLong : KSerializer<Long?> {
    override val descriptor = PrimitiveSerialDescriptor("CopyManga.Long", PrimitiveKind.LONG).nullable
    override fun deserialize(decoder: Decoder): Long? = decodeNumber(decoder, String::toLongOrNull)
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

internal object LenientInt : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("CopyManga.Int", PrimitiveKind.INT).nullable
    override fun deserialize(decoder: Decoder): Int? = decodeNumber(decoder, String::toIntOrNull)
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

internal object LenientDouble : KSerializer<Double?> {
    override val descriptor = PrimitiveSerialDescriptor("CopyManga.Double", PrimitiveKind.DOUBLE).nullable
    override fun deserialize(decoder: Decoder): Double? = decodeNumber(decoder) {
        it.toDoubleOrNull()?.takeIf(Double::isFinite)
    }
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

internal object LenientBoolean : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("CopyManga.Boolean", PrimitiveKind.BOOLEAN).nullable
    override fun deserialize(decoder: Decoder): Boolean? = decodeNumber(decoder) {
        when (it.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** Page positions must be integers; a null, object, or non-numeric position is an error. */
internal object LenientPositions : KSerializer<List<Int>?> {
    private val delegate = LenientList(RequiredPosition)
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<Int>? = delegate.deserialize(decoder)
    override fun serialize(encoder: Encoder, value: List<Int>?) = delegate.serialize(encoder, value)

    private object RequiredPosition : KSerializer<Int> {
        override val descriptor = PrimitiveSerialDescriptor("CopyManga.Position", PrimitiveKind.INT)
        override fun deserialize(decoder: Decoder): Int = LenientInt.deserialize(decoder)
            ?: throw SerializationException("CopyManga page positions must not be null.")
        override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    }
}

private inline fun <T> decodeNumber(decoder: Decoder, parse: (String) -> T?): T? {
    val json = decoder as? JsonDecoder ?: throw SerializationException("CopyManga values require JSON.")
    val element = json.decodeJsonElement()
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw SerializationException("Expected a CopyManga primitive value.")
    return parse(primitive.content.trim())
        ?: throw SerializationException("Invalid CopyManga value '${primitive.content.take(80)}'.")
}
