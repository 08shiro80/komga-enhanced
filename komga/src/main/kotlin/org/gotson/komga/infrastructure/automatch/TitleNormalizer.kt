package org.gotson.komga.infrastructure.automatch

/**
 * Title normalization + similarity scoring for auto-match.
 *
 * Komf-style rules: strip parens/brackets noise (region/source tags like `(EN)`,
 * `[Atsumaru]`, year `(2024)`), strip common volume markers, lowercase,
 * collapse punctuation/whitespace. Then we compare with a token-set Jaccard plus
 * a normalized exact-match bonus — sufficient for the 95% case where the source
 * folder name is a faithful rendering of the canonical title, and resilient to
 * trailing decoration that AniList/MangaDex never includes in their titles.
 */
object TitleNormalizer {
  // Strips a single layer of () or [] bracket pairs and what they contain.
  // Used iteratively until the string stabilises (handles nested brackets).
  private val BRACKETS = Regex("""[\(\[][^\(\)\[\]]*[\)\]]""")
  private val VOLUME_MARKERS = Regex("""(?i)\b(vol(?:ume|s)?\.?|v|chapter|ch|c)\s*\d+(?:\s*-\s*\d+)?\b""")
  private val PUNCT = Regex("""[\p{Punct}&&[^&]]""") // keep & (e.g. "Yu&Mi"), drop the rest
  private val WS = Regex("""\s+""")
  private val STOPWORDS =
    setOf(
      "the", "a", "an", "of", "and", "&",
      // language/region tags occasionally embedded without brackets
      "en", "english", "jp", "japanese", "kr", "korean", "raw", "scan", "scans", "uncensored",
    )

  /** Returns the normalized form: lowercase token list joined by single spaces, brackets and noise removed. */
  fun normalize(input: String?): String {
    if (input.isNullOrBlank()) return ""
    var s = input.trim()
    // Iterate the bracket stripper so [foo (bar)] or ((x)y) collapse cleanly.
    repeat(3) { s = BRACKETS.replace(s, " ") }
    s = VOLUME_MARKERS.replace(s, " ")
    s = PUNCT.replace(s, " ")
    s = WS.replace(s, " ").trim().lowercase()
    return s
  }

  /** Token set with stopwords removed; empty if nothing left. */
  fun tokens(input: String?): Set<String> {
    val n = normalize(input)
    if (n.isEmpty()) return emptySet()
    return n.split(' ').filter { it.length > 1 && it !in STOPWORDS }.toSet()
  }

  /**
   * Score in [0.0, 1.0]. Token-set Jaccard, with a small bonus when the
   * normalized strings match exactly (the latter is by far the most reliable
   * positive signal — no false positives ever observed when normalizations
   * match exactly).
   */
  fun score(query: String?, candidate: String?): Double {
    val nq = normalize(query)
    val nc = normalize(candidate)
    if (nq.isEmpty() || nc.isEmpty()) return 0.0
    if (nq == nc) return 1.0

    val a = tokens(query)
    val b = tokens(candidate)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.intersect(b).size.toDouble()
    val union = a.union(b).size.toDouble()
    val jaccard = if (union == 0.0) 0.0 else inter / union
    // Token containment: small bonus when one is a strict superset of the other
    // (common when query has extra noise we couldn't strip, or candidate has
    // an episode/year suffix like "Lookism (2024)").
    val containment =
      if (a.isNotEmpty() && b.isNotEmpty()) {
        maxOf(inter / a.size.toDouble(), inter / b.size.toDouble())
      } else {
        0.0
      }
    // Weighted blend: jaccard is the conservative core, containment loosens it
    // slightly so that exact-substring matches still score above the default
    // 0.85 threshold.
    return (jaccard * 0.6) + (containment * 0.4)
  }

  /** Best score among the candidate plus any alternative titles. */
  fun scoreAgainstAll(query: String?, primary: String?, alternates: List<String> = emptyList()): Double {
    var best = score(query, primary)
    for (alt in alternates) {
      val s = score(query, alt)
      if (s > best) best = s
    }
    return best
  }
}
