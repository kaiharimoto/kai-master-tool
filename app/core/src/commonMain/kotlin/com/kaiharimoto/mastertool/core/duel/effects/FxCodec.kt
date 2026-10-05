package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * The vocabulary's forgiving words: each sealed family is read through a serializer that keeps what it cannot read as
 * its `Unknown`, written back as it came — one unknown op inside an effect does not lose the rest of the script.
 */

/** An [Op] read forgivingly: a newer build's op is [Op.Unknown]. */
typealias ReadOp = @Serializable(LenientOp::class) Op

/** A [Filter] read forgivingly. */
typealias ReadFilter = @Serializable(LenientFilter::class) Filter

/** A [Cond] read forgivingly. */
typealias ReadCond = @Serializable(LenientCond::class) Cond

/** A [Proc] read forgivingly. */
typealias ReadProc = @Serializable(LenientProc::class) Proc

/** An [Opt] read forgivingly: an unread rule is never "no rule", which would make an effect unlimited. */
typealias ReadOpt = @Serializable(LenientOpt::class) Opt

/** A [Num] read forgivingly, and a constant written as a bare number. */
typealias ReadNum = @Serializable(LenientNum::class) Num

/**
 * Reads a [T] it knows as itself and one it does not as its unknown, written back as it came — `LenientAction`'s way,
 * for every family of the vocabulary.
 */
abstract class Lenient<T : Any>(
    private val base: () -> KSerializer<T>,
    private val unknown: (JsonObject) -> T,
    private val raw: (T) -> JsonObject?,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor by lazy { base().descriptor }

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as? JsonEncoder
        val kept = raw(value)
        if (kept != null && json != null) json.encodeJsonElement(kept)
        else encodeKnown(encoder, value)
    }

    protected open fun encodeKnown(encoder: Encoder, value: T) = encoder.encodeSerializableValue(base(), value)

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(base())
        val element = json.decodeJsonElement()
        return read(json.json, element)
    }

    protected open fun read(json: Json, element: JsonElement): T =
        runCatching { json.decodeFromJsonElement(base(), element) }
            .getOrElse { unknown(element as? JsonObject ?: JsonObject(mapOf(FxCodec.RAW to element))) }
}

object LenientOp : Lenient<Op>({ Op.serializer() }, { Op.Unknown(it) }, { (it as? Op.Unknown)?.raw })
object LenientFilter : Lenient<Filter>({ Filter.serializer() }, { Filter.Unknown(it) }, { (it as? Filter.Unknown)?.raw })
object LenientCond : Lenient<Cond>({ Cond.serializer() }, { Cond.Unknown(it) }, { (it as? Cond.Unknown)?.raw })
object LenientProc : Lenient<Proc>({ Proc.serializer() }, { Proc.Unknown(it) }, { (it as? Proc.Unknown)?.raw })
object LenientOpt : Lenient<Opt>({ Opt.serializer() }, { Opt.Unknown(it) }, { (it as? Opt.Unknown)?.raw })

/** A [Num]: a bare number is a [Num.Const], and a constant is written back as one. */
object LenientNum : Lenient<Num>({ Num.serializer() }, { Num.Unknown(it) }, { (it as? Num.Unknown)?.raw }) {
    override fun encodeKnown(encoder: Encoder, value: Num) {
        val json = encoder as? JsonEncoder
        if (value is Num.Const && json != null) json.encodeJsonElement(JsonPrimitive(value.n))
        else super.encodeKnown(encoder, value)
    }

    override fun read(json: Json, element: JsonElement): Num {
        val bare = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return if (bare != null) Num.Const(bare) else super.read(json, element)
    }
}

/** What reading a compiled script came to (D.md §3.2, §6). */
sealed interface FxRead {
    data class Script(val script: CardScript) : FxRead

    /** A newer vocabulary's script: kept as it is, never decoded, never rewritten. */
    data class Newer(val vocab: Int, val raw: JsonObject) : FxRead

    /** Not a script this build can read: the file is broken, or too large. */
    data class Bad(val why: String) : FxRead
}

/** The compiled scripts' JSON (`<data>/effects/<passcode>.json`, D.md §3.2): written compact, read forgivingly. */
object FxCodec {
    /** A script at most this many bytes (D.md §3.4, §7). */
    const val MAX_BYTES = 64 * 1024

    /** The key a non-object unknown is kept under. */
    const val RAW = "raw"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "t"
    }

    fun encode(s: CardScript): String = json.encodeToString(CardScript.serializer(), s)

    fun read(text: String): FxRead {
        if (text.encodeToByteArray().size > MAX_BYTES) return FxRead.Bad("The script is larger than 64 KB.")
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse { return FxRead.Bad("Not JSON: ${it.message ?: "unreadable"}") }
        val obj = element as? JsonObject ?: return FxRead.Bad("A script is one JSON object.")
        val vocab = obj["vocab"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: FxVocab.VERSION
        if (vocab > FxVocab.VERSION) return FxRead.Newer(vocab, obj)
        return runCatching { FxRead.Script(json.decodeFromJsonElement(CardScript.serializer(), obj)) }
            .getOrElse { FxRead.Bad(it.message?.lineSequence()?.firstOrNull() ?: "Not a script.") }
    }

    /** The script, or null when it is newer or unreadable. */
    fun decode(text: String): CardScript? = (read(text) as? FxRead.Script)?.script

    /**
     * The first 12 hex digits of the SHA-256 of the script as written: what a tag and a verdict name it by. The source's
     * hash ([CardScript.source]) is left out: the data is what is verified, not the JavaScript that built it.
     */
    fun hash(s: CardScript): String = Sha256.hex(encode(if (s.source.isEmpty()) s else s.copy(source = ""))).take(12)

    /** 12 hex of the SHA-256 of [text] as written: a source's ([CardScript.source]) or a card's printed text ([CardScript.text]). */
    fun short(text: String): String = Sha256.hex(text).take(12)

    /** A card's printed text's hash ([CardScript.text]): line endings and the ends' spaces aside, so only a real change shows. */
    fun textOf(printed: String): String = short(printed.replace("\r\n", "\n").trim())

    /**
     * The compiled file as the library writes it (`<data>/effects/<passcode>.json`): the script, with its vocabulary written
     * out even when it is this build's (D.md §6: "the compiled CardScript: vocab, its source's hash, the text's hash").
     */
    fun file(s: CardScript): String {
        val o = json.encodeToJsonElement(CardScript.serializer(), s) as JsonObject
        val head = LinkedHashMap<String, JsonElement>()
        o["card"]?.let { head["card"] = it }
        head["vocab"] = JsonPrimitive(s.vocab)
        o.forEach { (k, v) -> if (k != "card" && k != "vocab") head[k] = v }
        return JsonObject(head).toString()
    }
}
