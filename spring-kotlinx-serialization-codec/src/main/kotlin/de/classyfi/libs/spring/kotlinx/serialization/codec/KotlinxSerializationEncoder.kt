package de.classyfi.libs.spring.kotlinx.serialization.codec

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.reactivestreams.Publisher
import org.springframework.core.ResolvableType
import org.springframework.core.codec.AbstractEncoder
import org.springframework.core.codec.Hints
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.log.LogFormatUtils
import org.springframework.http.MediaType
import org.springframework.util.MimeType
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * @author Bernhard Frauendienst <bernhard.frauendienst@markt.de>
 */
@ExperimentalSerializationApi
class KotlinxSerializationEncoder private constructor(
  private val valueEncoder: ValueEncoder,
  vararg supportedMimeTypes: MimeType,
  private val streamingMediaTypes: List<MediaType>
) : AbstractEncoder<Any>(*supportedMimeTypes) {

  constructor(stringFormat: StringFormat, vararg supportedMimeTypes: MimeType,
              streamingMediaTypes: List<MediaType> = emptyList()) :
    this(ValueEncoder.FromString(stringFormat), *supportedMimeTypes, streamingMediaTypes = streamingMediaTypes)
  constructor(binaryFormat: BinaryFormat, vararg supportedMimeTypes: MimeType,
              streamingMediaTypes: List<MediaType> = emptyList()) :
    this(ValueEncoder.FromBytes(binaryFormat), *supportedMimeTypes, streamingMediaTypes = streamingMediaTypes)

  override fun canEncode(elementType: ResolvableType, mimeType: MimeType?): Boolean {
    if (!super.canEncode(elementType, mimeType)) {
      return false
    }
    return try {
      // TODO: cache this information
      valueEncoder.getSerializer(elementType)
      true
    } catch (e: SerializationException) {
      if (logger.isDebugEnabled) {
        logger.debug("No serializer found for type $elementType: ${e.message}")
      }
      false
    }
  }

  override fun encode(inputStream: Publisher<out Any>, bufferFactory: DataBufferFactory, elementType: ResolvableType, mimeType: MimeType?, hints: MutableMap<String, Any>?): Flux<DataBuffer> {
    val serializer = valueEncoder.getSerializer(elementType)

    return when (inputStream) {
      is Mono -> inputStream.map {
        bufferFactory.wrapEncodedValue(serializer, it, hints)
      }.flux()
      else -> {
        val streamingType = streamingMediaTypes.firstOrNull { it.isCompatibleWith(mimeType) }
        if (streamingType != null) {
          val separator = STREAM_SEPARATORS[streamingType] ?: NEWLINE_SEPARATOR
          Flux.from(inputStream).map { value ->
            bufferFactory.wrapEncodedValue(serializer, value, hints).also { buffer ->
              buffer.write(separator)
            }
          }
        } else {
          Flux.from(inputStream).collectList().map {
            bufferFactory.wrapEncodedValue(ListSerializer(serializer), it, hints)
          }.flux()
        }
      }
    }
  }

  override fun encodeValue(value: Any, bufferFactory: DataBufferFactory, valueType: ResolvableType, mimeType: MimeType?, hints: MutableMap<String, Any>?): DataBuffer {
    val serializer = valueEncoder.getSerializer(valueType)
    return bufferFactory.wrapEncodedValue(serializer, value, hints)
  }

  private fun <T> DataBufferFactory.wrapEncodedValue(serializer: KSerializer<T>, value: T, hints: MutableMap<String, Any>?): DataBuffer {
    if (!Hints.isLoggingSuppressed(hints)) {
      LogFormatUtils.traceDebug(logger) { traceOn ->
        val formatted = LogFormatUtils.formatValue(value, !traceOn)
        Hints.getLogPrefix(hints) + "Encoding [" + formatted + "]"
      }
    }

    return wrap(valueEncoder.encodeValue(serializer, value))
  }

  companion object {
    fun Json.asEncoder(vararg supportedMimeTypes: MimeType = DEFAULT_JSON_MIME_TYPES,
                       streamingMediaTypes: List<MediaType> = DEFAULT_JSON_STREAMING_MEDIA_TYPES)
      = KotlinxSerializationEncoder(this, *supportedMimeTypes, streamingMediaTypes = streamingMediaTypes)

    fun defaultJsonEncoder() = Json.asEncoder()

    private val DEFAULT_JSON_MIME_TYPES = arrayOf(
      MediaType.APPLICATION_JSON,
      MimeType("application", "*+json")
    )
    private val DEFAULT_JSON_STREAMING_MEDIA_TYPES = listOf(
      MediaType.APPLICATION_STREAM_JSON
    )
    private val NEWLINE_SEPARATOR = byteArrayOf('\n'.toByte())
    private val STREAM_SEPARATORS: Map<MediaType, ByteArray> = mapOf(
      MediaType.APPLICATION_STREAM_JSON to NEWLINE_SEPARATOR
    )
  }
}

@ExperimentalSerializationApi
private sealed class ValueEncoder(private val format: SerialFormat) {
  abstract fun <T> encodeValue(serializer: SerializationStrategy<T>, value: T): ByteArray

  fun getSerializer(elementType: ResolvableType): KSerializer<Any> {
    return format.serializersModule.serializer(elementType.type)
  }

  class FromString(private val format: StringFormat) : ValueEncoder(format) {
    override fun <T> encodeValue(serializer: SerializationStrategy<T>, value: T) =
      format.encodeToString(serializer, value).encodeToByteArray()
  }

  class FromBytes(private val format: BinaryFormat): ValueEncoder(format) {
    override fun <T> encodeValue(serializer: SerializationStrategy<T>, value: T) =
      format.encodeToByteArray(serializer, value)
  }
}
