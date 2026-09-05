package com.rimboard.keyboard.engine

import java.io.File

/**
 * The held-out prediction models, picked to match what each language ships.
 *
 * `tools/eval_ngrams.py --fixtures` writes three models per language, at
 * MIN_PAIR 3, 2 and 1, beside the sentences none of them were built from. Two
 * test classes read those files, and both used to name `pred2` outright.
 *
 * That was right when it was written and stopped being right two days later.
 * `build_ngrams.py` keeps a per-language override, `MIN_PAIR_BY_LANG`, and on
 * 2026-08-30 eleven languages moved to MIN_PAIR 1 -- including six of the eight
 * that have a split. From then on both arms were scoring a model no user has:
 * Croatian ships 9,342 rows and `pred2_hr` held 1,174.
 *
 * It went unnoticed because nothing connected the two files. So the threshold
 * is read here from the tool that builds the assets, rather than written down
 * in a test that has no reason to be re-read when the tool changes.
 */
object HeldOut {

    /** The default MIN_PAIR and the per-language overrides, from the builder. */
    fun minPair(): Pair<Int, Map<String, Int>> {
        val tool = listOf(File("../tools/build_ngrams.py"), File("tools/build_ngrams.py"))
            .firstOrNull { it.isFile }
            ?: error("tools/build_ngrams.py not found from " + File(".").absolutePath)
        val text = tool.readText()
        val block = Regex("""MIN_PAIR_BY_LANG = [{]([^}]*)[}]""").find(text)
            ?: error("MIN_PAIR_BY_LANG has moved or been renamed in " + tool)
        val body = block.groupValues[1].lines().joinToString(" ") { it.substringBefore('#') }
        val byLang = Regex("""([a-z][a-z])" *: *([0-9]+)""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        if (byLang.isEmpty()) error("no per-language MIN_PAIR parsed from " + tool)
        val default = Regex("""(?m)^MIN_PAIR = ([0-9]+)""").find(text)
            ?: error("the MIN_PAIR default has moved or been renamed in " + tool)
        return default.groupValues[1].toInt() to byLang
    }

    /** Which of the three written models [lang] actually ships. */
    fun minPairFor(lang: String): Int {
        val (default, byLang) = minPair()
        return byLang[lang] ?: default
    }

    /** The held-out model for [lang], at the threshold [lang] ships. */
    fun predictionsFor(dir: File, lang: String): String {
        val mp = minPairFor(lang)
        val f = File(dir, "pred" + mp + "_" + lang + ".txt")
        if (!f.isFile) {
            error(
                lang + " ships at MIN_PAIR " + mp + " and " + f.name + " was" +
                    " never written. Add that threshold to the tuple in" +
                    " tools/eval_ngrams.py and regenerate."
            )
        }
        return f.readText()
    }
}
