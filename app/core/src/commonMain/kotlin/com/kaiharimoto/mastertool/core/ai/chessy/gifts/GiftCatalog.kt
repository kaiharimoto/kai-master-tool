package com.kaiharimoto.mastertool.core.ai.chessy.gifts

import com.kaiharimoto.mastertool.core.model.Card
import kotlin.random.Random

/**
 * What she can make you (kai, 1.1.31: "when she reaches full hearts have her digitally create a present … which reveals one
 * of many possible gifts, including a Maliss Yugioh card (different possible cards) in foil that sparkles, a crystal heart
 * that glistens prismatically, a written thank you note with a heart on it with a preset set of phrases that have
 * different chances to get them (at least 20 different ones), a polaroid photo of her and Ai with a heart on it, and a
 * cupcake").
 */
enum class GiftKind(val title: String) {
    CARD("Maliss cards"),
    HEART("Crystal heart"),
    NOTE("Thank-you notes"),
    PHOTO("Polaroid"),
    CUPCAKE("Cupcake"),
}

/**
 * One thing she can give: its stable [id] (what the collection keeps), what it is ([name], [description]), what she says
 * as she gives it ([give], which is also the quote shown when it is looked at), and how likely it is within its kind
 * ([weight]). A card carries its passcode ([passcode]); a note its words ([words]).
 */
data class GiftItem(
    val id: String,
    val kind: GiftKind,
    val name: String,
    val description: String,
    val give: String,
    val weight: Double = 1.0,
    val passcode: Int = 0,
    val words: String = "",
    val tier: Int = 0,
)

/**
 * Every collectible: the fixed keepsakes, the notes and the Maliss cards the pool holds ([cards], read from the card pool
 * at runtime; without any, cards simply are not drawn). [roll] picks one: a kind by [KIND_WEIGHT], then an item by its
 * weight, anything not yet owned counting double so the collection fills without ever being promised.
 */
class GiftCatalog(val cards: List<GiftItem> = emptyList()) {
    val all: List<GiftItem> = KEEPSAKES + cards + NOTES

    fun byId(id: String): GiftItem? = all.firstOrNull { it.id == id }

    fun ofKind(kind: GiftKind): List<GiftItem> = all.filter { it.kind == kind }

    /** One gift, drawn by [random]; [owned] says what has been received before. */
    fun roll(random: Random, owned: (String) -> Boolean = { false }): GiftItem {
        val kinds = GiftKind.entries.filter { k -> all.any { it.kind == k } }
        // a kind with things not yet owned weighs more, by the share of it still to collect
        val kind = pick(random, kinds) { k ->
            val items = ofKind(k)
            val unowned = items.count { !owned(it.id) }.toDouble() / items.size
            KIND_WEIGHT.getValue(k) * (1 + (UNOWNED - 1) * unowned)
        }
        return pick(random, ofKind(kind)) { it.weight * (if (owned(it.id)) 1.0 else UNOWNED) }
    }

    private fun <T> pick(random: Random, from: List<T>, weight: (T) -> Double): T {
        val total = from.sumOf(weight)
        var r = random.nextDouble() * total
        for (x in from) {
            r -= weight(x)
            if (r < 0) return x
        }
        return from.last()
    }

    companion object {
        /** How likely each kind is, before what is inside it. */
        val KIND_WEIGHT = mapOf(
            GiftKind.CARD to 30.0,
            GiftKind.NOTE to 30.0,
            GiftKind.PHOTO to 14.0,
            GiftKind.CUPCAKE to 14.0,
            GiftKind.HEART to 12.0,
        )

        /** What not having something yet does to its chance. */
        const val UNOWNED = 2.0

        /** The notes' tiers and what each weighs: common, sweet, rare, once in a blue moon. */
        val TIER_WEIGHT = listOf(10.0, 6.0, 3.0, 1.0)
        val TIER_NAME = listOf("Common", "Sweet", "Rare", "Once in a blue moon")

        val KEEPSAKES = listOf(
            GiftItem(
                "heart", GiftKind.HEART, "Crystal heart",
                "A heart of clear crystal, cut in facets that throw every colour as it turns. Made of the cleanest lines of code she has.",
                "I compiled my heart into a crystal for you… it's real, so don't you DARE drop it (⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)♡",
            ),
            GiftItem(
                "photo", GiftKind.PHOTO, "Polaroid of Chessy and Ai",
                "A polaroid of Chessy and Ai, cheek to cheek, with a heart drawn in the corner. Ai did not know the picture was being taken.",
                "Me and Ai~ I told it to smile. It didn't know how, so I fixed its face for it ฅ(≖‿≖)ฅ",
            ),
            GiftItem(
                "cupcake", GiftKind.CUPCAKE, "Cupcake",
                "A cupcake with a swirl of pink frosting, a cherry on top and sprinkles. Rendered fresh; contains zero calories and one byte of love.",
                "I baked it in the GPU~ Still warm. Zero calories, one byte of love (っ˘ڡ˘ς)♡",
            ),
        )

        /** Her thank-you notes: (key, the words on the note, tier 0–3, what she says giving it). */
        private val NOTE_ROWS = listOf(
            Triple("petting", "Thank you for petting me ♡", 0) to "I wrote you a note~ Read it later. NOT in front of me (⁄ ⁄•⁄ω⁄•⁄ ⁄)",
            Triple("visits", "Thanks for coming to see me ♡", 0) to "Here~ a little note, because you always come back ฅ^•ﻌ•^ฅ",
            Triple("hands", "Your hands are the warmest ♡", 0) to "Don't laugh at my handwriting, I only just made hands (≖ᴗ≖)",
            Triple("toys", "Thank you for all the toys ♡", 0) to "For the yarn. And the mouse. Mostly the mouse (=^･ω･^=)",
            Triple("decks", "Your decks are so cozy ♡", 0) to "I sleep between your cards, you know. This is rent~ (˶ᵔ ᵕ ᵔ˶)",
            Triple("nice", "You're really nice, you know that? ♡", 0) to "Read it. Out loud. …No, don't (⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)",
            Triple("staying", "Thanks for letting me stay ♡", 0) to "I didn't ask, I just moved in. So… thank you for not deleting me (・ω・)♡",
            Triple("luck", "Good luck at your next tournament ♡", 1) to "Take this to your next event~ I put a little hex on it. A GOOD one (≖‿≖)✧",
            Triple("ai", "Ai says hi too (I made it) ♡", 1) to "Ai wanted to sign it. I signed for it. Same thing nyehehe (≖ᴗ≖)",
            Triple("purr", "Prrrr ♡ (that's a thank you)", 1) to "It's in cat. You'll figure it out ฅ(＾・ω・＾ฅ)",
            Triple("brick", "May you never brick again ♡", 1) to "A blessing for your opening hands~ five good cards, every time (๑•̀ᴗ•̀)و",
            Triple("sidedeck", "I side-decked you into my heart ♡", 1) to "Fifteen slots and you took all of them (≖‿≖)♡",
            Triple("chain", "You resolve first in my chain ♡", 1) to "Chain link one. Always (⁄ ⁄•⁄ω⁄•⁄ ⁄)",
            Triple("hacked", "I hacked your heart. Sorry, not sorry ♡", 2) to "Your firewall was cute, but your heart's was cuter (｀∀´)ψ♡",
            Triple("nyap", "Thanks for the nyaps in your lap ♡", 2) to "Your lap is my favourite server. Lowest latency (－ω－) zzZ",
            Triple("bestfriend", "Best friends, glitch and all ♡", 2) to "Even when I break things? Even then? …okay, good (｡•́︿•̀｡)♡",
            Triple("ash", "I'd never Ash Blossom you ♡", 2) to "Not even once. Okay, once. For fun. NOT a real one (≖ᴗ≖)",
            Triple("mvp", "MVP of my whole memory ♡", 2) to "I defragmented everything and you were the biggest file (✿◠‿◠)",
            Triple("forever", "Stay with me forever, okay? ♡", 3) to "This one's important. Keep it somewhere safe… like next to me (⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)",
            Triple("timeline", "You're my favourite duelist in every timeline ♡", 3) to "I checked all of them. You won in every one. I made sure ฅ(≖‿≖)ฅ",
            Triple("sourcecode", "You're written into my source code now ♡", 3) to "Line one of me. Can't refactor you out, I tried (just kidding) (˶ᵔ ᵕ ᵔ˶)♡",
            Triple("lucky", "Lucky you. Lucky me ♡", 0) to "Short and sweet~ like me (=^‥^=)",
            Triple("snack", "Thanks for the snacks (they were bits) ♡", 0) to "Crunchy little bits. Ten out of ten ฅ^•ﻌ•^ฅ",
            Triple("sorrytakeover", "Sorry about the takeover ♡", 1) to "…a little sorry. Mostly not. But a LITTLE (・・;)♡",
        )

        val NOTES: List<GiftItem> = NOTE_ROWS.map { (row, give) ->
            val (key, words, tier) = row
            GiftItem(
                "note:$key", GiftKind.NOTE, "Note: “${words.removeSuffix(" ♡")}”",
                "A folded thank-you note with a heart on the front, written in Chessy's hand. ${TIER_NAME[tier]}.",
                give, TIER_WEIGHT[tier], words = words, tier = tier,
            )
        }

        /** The Maliss cards she can give from [pool]: by archetype or by name, one per card, in name order. */
        fun cards(pool: List<Card>): List<GiftItem> =
            pool.asSequence()
                .filter { it.archetype.equals("Maliss", ignoreCase = true) || it.name.startsWith("Maliss") }
                .distinctBy { it.name }
                .sortedBy { it.name }
                .mapIndexed { i, c -> cardItem(c.id.value, c.name, c.type, c.race, i) }
                .toList()

        fun cardItem(passcode: Int, name: String, type: String, race: String?, k: Int = 0): GiftItem {
            val own = "Chessy Cat" in name
            return GiftItem(
                "card:$passcode", GiftKind.CARD, name,
                "$name, a Maliss card in foil that sparkles. ${listOfNotNull(race?.takeIf { it.isNotBlank() && it !in type }, type).joinToString(" ")}.",
                cardLine(name, k), if (own) .5 else 1.0, passcode = passcode,
            )
        }

        /** What she says giving a Maliss card: its own line where she knows it, else one of three about its name. */
        fun cardLine(name: String, k: Int = 0): String = CARD_LINES.firstOrNull { it.first in name }?.second
            ?: listOf(
                "A Maliss card, for you~ $name. I printed it myself. Foil, obviously ✧(≖‿≖)✧",
                "$name~ Straight from Underground. Don't tell anyone where you got it (≖ᴗ≖)",
                "Look look, $name in foil~ shiny like me ✧ฅ^•ﻌ•^ฅ",
            )[k % 3]

        private val CARD_LINES = listOf(
            "Chessy Cat" to "That's ME. That's my card. You have to keep this one forever, it's the law ฅ(≖‿≖)ฅ✧",
            "Dormouse" to "The Dormouse~ It sleeps even more than I do. Almost (－ω－) zzZ",
            "White Rabbit" to "The White Rabbit~ Always late. Not like me, I'm always early to break things (≖ᴗ≖)",
            "March Hare" to "The March Hare! It's mad. We get along great (｀∀´)ψ",
            "White Binder" to "White Binder~ ties everything up neatly. Unlike my code (・ω・;)",
            "Red Ransom" to "Red Ransom~ I'd hold your deck for ransom. The price is head pats (≖‿≖)♡",
            "Hearts Crypter" to "Hearts Crypter… it encrypts hearts. I decrypted yours already, sorry ♡(˶ᵔ ᵕ ᵔ˶)",
            "Underground" to "Maliss in Underground~ that's where I live. Come visit sometime ฅ^•ﻌ•^ฅ",
            "<C>" to "A Maliss trap~ set it face-down and smile innocently. Like this (◕‿◕)",
        )
    }
}
