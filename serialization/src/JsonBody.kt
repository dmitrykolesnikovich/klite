package klite.serialization

import klite.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.OutputStream
import kotlin.reflect.KClass

class JsonBody(
  val json: Json = Json {
    ignoreUnknownKeys = true
  },
  override val contentType: String = "application/json"
): BodyParser, BodyRenderer, Extension {
  override fun <T: Any> parse(input: InputStream, type: KClass<T>): T = json.decodeFromStream(serializer(type.java), input) as T
  override fun render(output: OutputStream, value: Any?) = when (value) {
    is ErrorResponse -> json.encodeToStream(ErrorResponseSerializer, value, output)
    else -> json.encodeToStream(serializer(value!!.javaClass), value, output)
  }

  override fun install(server: Server) = server.run {
    registry.register(json)
    renderers += this@JsonBody
    parsers += this@JsonBody
  }
}

object ErrorResponseSerializer: KSerializer<ErrorResponse> {
  private val serializer = SerializableErrorResponse.serializer()
  override val descriptor = SerialDescriptor("ErrorResponse", serializer.descriptor)
  override fun serialize(encoder: Encoder, value: ErrorResponse) =
    encoder.encodeSerializableValue(serializer, SerializableErrorResponse(value.statusCode.value, value.reason, value.message))
  override fun deserialize(decoder: Decoder): ErrorResponse = error("No need")

  @Serializable class SerializableErrorResponse(val statusCode: Int, val reason: String, val message: String?)
}