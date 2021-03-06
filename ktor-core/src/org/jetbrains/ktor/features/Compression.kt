package org.jetbrains.ktor.features

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.cio.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.request.*
import org.jetbrains.ktor.util.*

/**
 * Compression feature configuration
 */
data class CompressionOptions(
        /**
         * Map of encoder configurations
         */
        val encoders: Map<String, CompressionEncoderConfig> = emptyMap(),
        /**
         * Conditions for all encoders
         */
        val conditions: List<ApplicationCall.(FinalContent) -> Boolean> = emptyList()
)

/**
 * Configuration for an encoder
 */
data class CompressionEncoderConfig(
        /**
         * Name of the encoder, matched against entry in `Accept-Encoding` header
         */
        val name: String,
        /**
         * Encoder implementation
         */
        val encoder: CompressionEncoder,
        /**
         * Conditions for the encoder
         */
        val conditions: List<ApplicationCall.(FinalContent) -> Boolean>,
        /**
         * Priority of the encoder
         */
        val priority: Double)

/**
 * Feature to compress a response based on conditions and ability of client to decompress it
 */
class Compression(compression: Configuration) {
    private val options = compression.build()
    private val comparator = compareBy<Pair<CompressionEncoderConfig, HeaderValue>>({ it.second.quality }, { it.first.priority }).reversed()

    private suspend fun interceptor(call: ApplicationCall) {
        val acceptEncodingRaw = call.request.acceptEncoding()
        if (acceptEncodingRaw == null || call.isCompressionSuppressed())
            return

        val encoders = parseHeaderValue(acceptEncodingRaw)
                .filter { it.value == "*" || it.value in options.encoders }
                .flatMap { header ->
                    when (header.value) {
                        "*" -> options.encoders.values.map { it to header }
                        else -> options.encoders[header.value]?.let { listOf(it to header) } ?: emptyList()
                    }
                }
                .sortedWith(comparator)
                .map { it.first }

        if (!encoders.isNotEmpty())
            return

        call.response.pipeline.intercept(ApplicationResponsePipeline.ContentEncoding) {
            val message = subject
            if (message is FinalContent
                    && message !is CompressedResponse
                    && options.conditions.all { it(call, message) }
                    && !call.isCompressionSuppressed()
                    && message.headers[HttpHeaders.ContentEncoding].let { it == null || it == "identity" }
                    ) {

                val encoderOptions = encoders.firstOrNull { it.conditions.all { it(call, message) } }

                val channel: () -> ReadChannel = when (message) {
                    is FinalContent.ReadChannelContent -> ({ message.readFrom() })
                    is FinalContent.WriteChannelContent -> {
                        if (encoderOptions != null) {
                            proceedWith(CompressedWriteResponse(message, encoderOptions.name, encoderOptions.encoder))
                        }
                        return@intercept
                    }
                    is FinalContent.NoContent -> return@intercept
                    is FinalContent.ByteArrayContent -> ({ message.bytes().toReadChannel() })
                    is FinalContent.ProtocolUpgrade -> return@intercept
                }

                if (encoderOptions != null) {
                    proceedWith(CompressedResponse(channel, message.headers, encoderOptions.name, encoderOptions.encoder))
                }
            }
        }
    }

    private class CompressedResponse(val delegateChannel: () -> ReadChannel, val delegateHeaders: ValuesMap, val encoding: String, val encoder: CompressionEncoder) : FinalContent.ReadChannelContent() {
        override fun readFrom() = encoder.compress(delegateChannel())
        override val headers by lazy {
            ValuesMap.build(true) {
                appendFiltered(delegateHeaders) { name, _ -> !name.equals(HttpHeaders.ContentLength, true) }
                append(HttpHeaders.ContentEncoding, encoding)
            }
        }
    }

    private class CompressedWriteResponse(val delegate: WriteChannelContent, val encoding: String, val encoder: CompressionEncoder) : FinalContent.WriteChannelContent() {
        override val headers by lazy {
            ValuesMap.build(true) {
                appendFiltered(delegate.headers) { name, _ -> !name.equals(HttpHeaders.ContentLength, true) }
                append(HttpHeaders.ContentEncoding, encoding)
            }
        }

        override suspend fun writeTo(channel: WriteChannel) {
            delegate.writeTo(encoder.compress(channel))
        }
    }

    /**
     * `ApplicationFeature` implementation for [Compression]
     */
    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Configuration, Compression> {
        val SuppressionAttribute = AttributeKey<Boolean>("preventCompression")

        override val key = AttributeKey<Compression>("Compression")
        override fun install(pipeline: ApplicationCallPipeline, configure: Configuration.() -> Unit): Compression {
            val config = Configuration().apply(configure)
            if (config.encoders.none())
                config.default()

            val feature = Compression(config)
            pipeline.intercept(ApplicationCallPipeline.Infrastructure) { feature.interceptor(call) }
            return feature
        }
    }

    /**
     * Configuration builder for Compression feature
     */
    class Configuration() : ConditionsHolderBuilder {
        val encoders = hashMapOf<String, CompressionEncoderBuilder>()
        override val conditions = arrayListOf<ApplicationCall.(FinalContent) -> Boolean>()

        /**
         * Appends an encoder to the configuration
         */
        fun encoder(name: String, encoder: CompressionEncoder, block: CompressionEncoderBuilder.() -> Unit = {}) {
            require(name.isNotBlank()) { "encoder name couldn't be blank" }
            if (name in encoders) {
                throw IllegalArgumentException("Encoder $name is already registered")
            }

            encoders[name] = CompressionEncoderBuilder(name, encoder).apply(block)
        }

        /**
         * Appends default configuration
         */
        fun default() {
            gzip()
            deflate()
            identity()
        }

        /**
         * Builds `CompressionOptions`
         */
        fun build() = CompressionOptions(
                encoders = encoders.mapValues { it.value.build() },
                conditions = conditions.toList()
        )
    }

}

private fun ApplicationCall.isCompressionSuppressed() = Compression.SuppressionAttribute in attributes

/**
 * Represents a Compression encoder
 */
interface CompressionEncoder {
    /**
     * Wraps [readChannel] into a compressing [ReadChannel]
     */
    fun compress(readChannel: ReadChannel): ReadChannel

    /**
     * Wraps [writeChannel] into a compressing [WriteChannel]
     */
    fun compress(writeChannel: WriteChannel): WriteChannel
}

/**
 * Implementation of the gzip encoder
 */
object GzipEncoder : CompressionEncoder {
    override fun compress(readChannel: ReadChannel) = readChannel.deflated(true)
    override fun compress(writeChannel: WriteChannel) = writeChannel.deflated(true)
}

/**
 * Implementation of the deflate encoder
 */
object DeflateEncoder : CompressionEncoder {
    override fun compress(readChannel: ReadChannel) = readChannel.deflated(false)
    override fun compress(writeChannel: WriteChannel) = writeChannel.deflated(false)
}

/**
 *  Implementation of the identity encoder
 */
object IdentityEncoder : CompressionEncoder {
    override fun compress(readChannel: ReadChannel) = readChannel
    override fun compress(writeChannel: WriteChannel) = writeChannel
}

/**
 * Represents a builder for conditions
 */
interface ConditionsHolderBuilder {
    val conditions: MutableList<ApplicationCall.(FinalContent) -> Boolean>
}

/**
 * Builder for compression encoder configuration
 */
class CompressionEncoderBuilder internal constructor(val name: String, val encoder: CompressionEncoder) : ConditionsHolderBuilder {
    /**
     * List of conditions for this encoder
     */
    override val conditions = arrayListOf<ApplicationCall.(FinalContent) -> Boolean>()

    /**
     * Priority for this encoder
     */
    var priority: Double = 1.0

    /**
     * Builds [CompressionEncoderConfig] instance
     */
    fun build(): CompressionEncoderConfig {
        return CompressionEncoderConfig(name, encoder, conditions.toList(), priority)
    }
}


/**
 * Appends `gzip` encoder
 */
fun Compression.Configuration.gzip(block: CompressionEncoderBuilder.() -> Unit = {}) {
    encoder("gzip", GzipEncoder, block)
}

/**
 * Appends `deflate` encoder with default priority of 0.9
 */
fun Compression.Configuration.deflate(block: CompressionEncoderBuilder.() -> Unit = {}) {
    encoder("deflate", DeflateEncoder) {
        priority = 0.9
        block()
    }
}

/**
 * Appends `identity` encoder
 */
fun Compression.Configuration.identity(block: CompressionEncoderBuilder.() -> Unit = {}) {
    encoder("identity", IdentityEncoder, block)
}

/**
 * Appends a custom condition to the encoder or Compression configuration
 */
fun ConditionsHolderBuilder.condition(predicate: ApplicationCall.(FinalContent) -> Boolean) {
    conditions.add(predicate)
}

/**
 * Appends a minimum size condition to the encoder or Compression configuration
 */
fun ConditionsHolderBuilder.minimumSize(minSize: Long) {
    condition { it.contentLength()?.let { it >= minSize } ?: true }
}

/**
 * Appends a content type condition to the encoder or Compression configuration
 */
fun ConditionsHolderBuilder.matchContentType(vararg mimeTypes: ContentType) {
    condition {
        it.contentType()?.let { mimeType ->
            mimeTypes.any { mimeType.match(it) }
        } ?: false
    }
}

/**
 * Appends a content type exclusion condition to the encoder or Compression configuration
 */
fun ConditionsHolderBuilder.excludeContentType(vararg mimeTypes: ContentType) {
    condition {
        it.contentType()?.let { mimeType ->
            mimeTypes.none { mimeType.match(it) }
        } ?: false
    }
}
