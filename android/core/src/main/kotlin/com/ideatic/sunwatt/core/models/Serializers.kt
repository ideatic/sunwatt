package com.ideatic.sunwatt.core.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * The one JSON configuration of the app. Every stored field has a default, unknown keys are ignored and a `null` or an
 * unknown value of a field with a default reads as the default, so data written by older (or newer) versions loads.
 */
val SunWattJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Stored enums are written as their `rawValue` (the same strings as the Swift enums). With a [fallback] an unknown or
 * unreadable value decodes to it (settings from a newer version); without one it throws [SerializationException].
 */
open class RawValueEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val entries: List<T>,
    private val rawValue: (T) -> String,
    private val fallback: T? = null,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(rawValue(value))

    override fun deserialize(decoder: Decoder): T {
        val raw = try {
            decoder.decodeString()
        } catch (e: SerializationException) {
            return fallback ?: throw e
        }
        return entries.firstOrNull { rawValue(it) == raw }
            ?: fallback
            ?: throw SerializationException("Unknown ${descriptor.serialName}: $raw")
    }
}

/** A `Date` the way Swift's `.iso8601` strategy stores it: UTC, whole seconds ("2026-06-01T10:30:00Z"). */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.truncatedTo(ChronoUnit.SECONDS).toString())

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (e: java.time.format.DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e2: java.time.format.DateTimeParseException) {
                throw SerializationException("Invalid date: $text")
            }
        }
    }
}

/**
 * `SunWattSettings.additionalPlants`: a list that cannot be read as a whole is dropped (empty), not an error, so a
 * broken plant never loses the other settings. Same as the Swift `try?` on the whole list.
 */
object LenientPlantListSerializer : KSerializer<List<SolarPlant>> {
    private val delegate = ListSerializer(SolarPlant.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<SolarPlant>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<SolarPlant> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        return try {
            jsonDecoder.json.decodeFromJsonElement(delegate, element)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }
}
