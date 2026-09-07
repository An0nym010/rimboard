package com.rimboard.keyboard.engine

import com.rimboard.keyboard.model.PersonalCase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The population [PersonalCase] draws from, measured, and the one question
 * about it this repository can actually answer.
 *
 * The obvious test to write here is an accuracy one: walk the prose fixtures
 * as if they were being typed, ask the rule at every mid-sentence word what it
 * would offer, and count. **It was written, and it measures nothing.** Over
 * 23,712 mid-sentence words in twenty-two languages the rule forms an opinion
 * **25 times** -- because a fixture is two hundred edited sentences, which
 * gives a word two or three sightings, and the rule needs a lead of
 * [PersonalCase.MIN_LEAD] before it will say anything at all. Nineteen of those
 * twenty-five were right and six were wrong, and no figure worth quoting can
 * be built on twenty-five.
 *
 * They are worth reading rather than counting, though, because all twenty-five
 * come from two languages. The nineteen right are German nouns and the
 * Indonesian polite `Anda` -- `Auto`, `Tag`, `Nachricht`, `Leben`, `Jungen`,
 * `Sie` -- and **all six wrong are one word**: `sie` offered `Sie`, which is
 * the residue the second half of this file is about.
 *
 * That is not a defect in the fixtures. It is the finding, and it is the same
 * one `open-items` already recorded about case memory before any of this was
 * built: **the subject of this feature is a person's own repeated vocabulary,
 * and no corpus in this repository is a person.** A fixture's words are spread
 * thin by design; a person's are not. Recorded here so that nobody prices this
 * from the fixtures again and reports a number that means nothing -- which is
 * exactly how the prefix glide decoder was measured against the wrong
 * population and believed.
 *
 * ## What *can* be measured here, and is
 *
 * The rule rests on one assumption, and the fixtures answer it directly:
 * **that a word has a habitual casing at all.** A majority vote is only worth
 * keeping if there is a majority to find. Measured 2026-09-08 over every
 * mid-sentence word of all twenty-two fixtures (sentence openers excluded,
 * since their capital is auto-capitalisation's):
 *
 *     distinct words                      13,052
 *     ever capitalised mid-sentence          459   (3.5%)
 *     seen twice or more                   2,882
 *     of those, written more than one way     12   (0.4%)
 *
 * **99.6% of words that appear twice are written the same way both times**,
 * which is the premise holding. And the 0.4% that are not is not a scatter of
 * noise -- it is a list short enough to read, and it has a shape:
 *
 *     de  Sie 13 / sie 6      Ihnen 1 / ihnen 1     Mal, Morgen
 *     ru  Вы 1 / вы 3
 *     id  Anda 7 / anda 1     Ada 1 / ada 12
 *     es  Me 1 / me 16        Copa, Mundo
 *     nl  Ken 2 / ken 1
 *     uk  Тому 1 / тому 2
 *
 * **Most of the residue is the polite second person** -- German `Sie` and
 * `Ihnen`, Russian `Вы`, Indonesian `Anda`. Those are capitalised as a
 * courtesy to the person being written to, not because the word is spelled
 * that way, so they are a case where a word genuinely has two habitual
 * spellings and this rule will pick one. The rest are a noun against an adverb
 * (`Mal`, `Morgen`), a name against a verb (`Ken`), and fragments of proper
 * nouns (`Copa Mundo`).
 *
 * Worth knowing rather than worth fixing: nothing counted here can separate
 * those two senses, the cost of picking wrong is a chip that is not tapped,
 * and the languages carrying the residue are also the ones carrying most of
 * the benefit -- German capitalises **206 of its 624** distinct mid-sentence
 * words, where every other language is between 1 and 21.
 */
class PersonalCasePopulationTest {

    private fun fixtures(): File =
        listOf(File("src/test/fixtures"), File("app/src/test/fixtures")).first { it.isDirectory }

    private fun langs(): List<String> =
        fixtures().listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith("prose_") && it.endsWith(".txt") }
            .map { it.removePrefix("prose_").removeSuffix(".txt") }
            .sorted()

    /**
     * Every mid-sentence word of one fixture, in order.
     *
     * Judged only if it is a plain word -- letters and the straight apostrophe
     * -- of a length [UserData.learnWord] would accept, and is not the first
     * word of a sentence, whose capital belongs to auto-capitalisation rather
     * than to the writer. An all-capitals token is skipped too: on a phone
     * that is typed with caps lock, and [PersonalCase.counts] refuses those.
     */
    private fun midSentenceWords(lang: String): List<String> {
        val out = ArrayList<String>()
        for (line in File(fixtures(), "prose_$lang.txt").readLines()) {
            var sentenceInitial = true
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (!c.isLetter() && c != '\'') {
                    if (c == '.' || c == '!' || c == '?') sentenceInitial = true
                    i++
                    continue
                }
                var j = i
                while (j < line.length && (line[j].isLetter() || line[j] == '\'')) j++
                val word = line.substring(i, j)
                i = j
                val initial = sentenceInitial
                sentenceInitial = false
                if (initial) continue
                if (word.length < 2 || word.length > 24) continue
                if (word.none { it.isLowerCase() }) continue
                out.add(word)
            }
        }
        return out
    }

    @Test
    fun `the fixtures cannot price this rule, and this is the number that says so`() {
        // The accuracy walk, kept rather than described. Walking a fixture as
        // if it were being typed and asking the rule before each word is the
        // obvious way to measure this, and the point of running it is that it
        // comes back nearly empty -- so the emptiness is asserted, and the
        // next person gets the number instead of the idea.
        var judged = 0
        var right = 0
        var wrong = 0
        val calls = ArrayList<String>()
        for (lang in langs()) {
            val locale = Locale.forLanguageTag(lang)
            val votes = HashMap<String, Pair<String, Int>>()
            for (word in midSentenceWords(lang)) {
                val key = word.lowercase(locale)
                judged++
                val held = votes[key]
                val offered = held?.let { PersonalCase.formFor(key, it.first, it.second) }
                if (offered != null) {
                    if (offered == word) right++ else wrong++
                    calls.add("$lang $word<-$offered")
                }
                votes[key] = PersonalCase.vote(held?.first ?: "", held?.second ?: 0, word)
            }
        }
        println("judged $judged, opinions ${right + wrong} (right $right, wrong $wrong)")
        println("every opinion the fixtures produced: " + calls.joinToString(", "))

        assertTrue("the walk stopped finding words", judged > 20_000)
        assertTrue(
            "the rule now forms ${right + wrong} opinions over $judged " +
                "mid-sentence words, where it formed 25. Something has given " +
                "the fixtures enough repetition per word to reach the bar, " +
                "which would make an accuracy figure from this population " +
                "worth computing -- and this test is the reason nobody has " +
                "been computing one.",
            right + wrong < 200
        )
    }

    @Test
    fun `a word written twice is written the same way both times`() {
        var distinct = 0
        var capitalised = 0
        var repeated = 0
        var inconsistent = 0
        val residue = ArrayList<String>()
        val report = StringBuilder("lang  distinct  capitalised  seen2+  two-ways\n")

        for (lang in langs()) {
            val locale = Locale.forLanguageTag(lang)
            val forms = HashMap<String, HashMap<String, Int>>()
            for (w in midSentenceWords(lang)) {
                forms.getOrPut(w.lowercase(locale)) { HashMap() }.merge(w, 1) { a, b -> a + b }
            }
            var langCap = 0
            var langRepeated = 0
            var langSplit = 0
            for ((key, seen) in forms) {
                if (seen.keys.any { it != key }) langCap++
                if (seen.values.sum() < 2) continue
                langRepeated++
                if (seen.size > 1) {
                    langSplit++
                    residue.add("$lang " + seen.entries.joinToString("/") { "${it.key} ${it.value}" })
                }
            }
            distinct += forms.size
            capitalised += langCap
            repeated += langRepeated
            inconsistent += langSplit
            report.append(
                "%-4s %9d %12d %7d %9d\n".format(lang, forms.size, langCap, langRepeated, langSplit)
            )
        }

        val consistency = 100.0 * (repeated - inconsistent) / repeated
        report.append(
            "ALL  %9d %12d %7d %9d   consistency %.1f%%\n"
                .format(distinct, capitalised, repeated, inconsistent, consistency)
        )
        report.append("written both ways: ").append(residue.joinToString("; "))
        println(report)

        assertTrue(
            "the walk found $distinct distinct mid-sentence words, which means " +
                "the tokeniser has stopped matching the fixtures and every " +
                "figure below it is measuring nothing",
            distinct > 8_000 && repeated > 1_500
        )
        assertTrue(
            "only $capitalised distinct words are ever capitalised " +
                "mid-sentence. There is nothing here for a rule about capitals " +
                "to be about, so either the fixtures or the sentence-boundary " +
                "walk has changed.",
            capitalised > 200
        )
        // Measured at 99.6%. The floor sits well under it because what would
        // move this number is a fixture set being rebuilt, not a code change,
        // and the question it answers is qualitative: is there a habitual
        // spelling to find? A drop to 97% would still say yes and would still
        // be worth knowing about; a drop past it would mean the majority vote
        // is picking between two live spellings often enough that offering
        // either one is a guess, and this rule would want re-arguing.
        assertTrue(
            "words that appear twice are written the same way both times only " +
                "%.1f%% of the time, so a majority vote is choosing between two " +
                "live spellings rather than finding a habit. See this file's " +
                "notes on the polite second person, which is what the residue " +
                "was made of when it was 0.4%%.".format(consistency),
            consistency >= 97.0
        )
    }
}
