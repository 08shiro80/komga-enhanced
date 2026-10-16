package org.gotson.komga.infrastructure.search

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexUpgrader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

private const val MAX_RESULTS = 1000

@Component
class LuceneHelper(
  private val directory: Directory,
  private val searchAnalyzer: Analyzer,
  private val indexAnalyzer: Analyzer,
  private val indexWriter: IndexWriter,
  private val searcherManager: SearcherManager,
  private val luceneCommitter: LuceneCommitter,
) {
  fun indexExists(): Boolean = DirectoryReader.indexExists(directory)

  fun setIndexVersion(version: Int) {
    val doc =
      Document().apply {
        add(StringField("index_version", version.toString(), Field.Store.YES))
        add(StringField("type", "index_version", Field.Store.NO))
      }
    updateDocument(Term("type", "index_version"), doc)
    logger.info { "Lucene index version: ${getIndexVersion()}" }
  }

  fun getIndexVersion(): Int {
    val searcher = searcherManager.acquire()
    try {
      val topDocs = searcher.search(TermQuery(Term("type", "index_version")), 1)
      return topDocs.scoreDocs
        .map { searcher.storedFields().document(it.doc)["index_version"] }
        .firstOrNull()
        ?.toIntOrNull() ?: 1
    } finally {
      searcherManager.release(searcher)
    }
  }

  fun searchEntitiesIds(
    searchTerm: String?,
    entity: LuceneEntity,
  ): List<String>? =
    if (!searchTerm.isNullOrBlank()) {
      try {
        val escaped = escapeUserSearch(searchTerm.trim())
        val terms = escaped.split("\\s+".toRegex())
        val query =
          if (terms.size > 1 && terms.last().length < 3 && terms.last().matches(Regex("\\w+"))) {
            terms.dropLast(1).joinToString(" ") + " " + terms.last() + "*"
          } else {
            escaped
          }
        val fieldsQuery =
          MultiFieldQueryParser(entity.defaultFields, searchAnalyzer)
            .apply {
              defaultOperator = QueryParser.Operator.AND
            }.parse("$query *:*")

        val typeQuery = TermQuery(Term(LuceneEntity.TYPE, entity.type))

        val booleanQuery =
          BooleanQuery
            .Builder()
            .add(fieldsQuery, BooleanClause.Occur.MUST)
            .add(typeQuery, BooleanClause.Occur.MUST)
            .build()

        val searcher = searcherManager.acquire()
        try {
          val topDocs = searcher.search(booleanQuery, MAX_RESULTS)
          topDocs.scoreDocs.map { searcher.storedFields().document(it.doc)[entity.id] }
        } finally {
          searcherManager.release(searcher)
        }
      } catch (_: ParseException) {
        emptyList()
      } catch (e: Exception) {
        logger.error(e) { "Error fetching entities from index" }
        emptyList()
      }
    } else {
      null
    }

  private fun escapeUserSearch(input: String): String {
    val knownFields =
      setOf(
        "title",
        "isbn",
        "tag",
        "author",
        "role",
        "release_date",
        "status",
        "deleted",
        "oneshot",
        "publisher",
        "reading_direction",
        "age_rating",
        "language",
        "genre",
        "sharing_label",
        "total_book_count",
        "book_count",
        "complete",
        "series_tag",
        "book_tag",
        "name",
        "writer",
        "penciller",
        "colorist",
        "letterer",
        "cover",
        "editor",
        "translator",
        "inker",
      )

    val fieldAlternation = knownFields.joinToString("|")
    val rangeRegex = Regex("""(?i)(?:$fieldAlternation):[{\[]\S+\s+TO\s+\S+[}\]]""")

    val protectedRanges = mutableMapOf<String, String>()
    var processed = input
    rangeRegex.findAll(input).toList().reversed().forEach { match ->
      val placeholder = "__RANGE${protectedRanges.size}__"
      protectedRanges[placeholder] = match.value
      processed = processed.replaceRange(match.range, placeholder)
    }

    val tokenRegex = Regex("""\S*"[^"]*"\S*|\S+""")
    return tokenRegex
      .findAll(processed)
      .joinToString(" ") { match ->
        val term = match.value
        if (term in protectedRanges) {
          protectedRanges[term]!!
        } else {
          val unsigned = term.removePrefix("+").removePrefix("-")
          val colonIdx = unsigned.indexOf(':')
          val keepAsIs =
            (term.contains('"') && term.count { it == '"' } % 2 == 0) ||
              (colonIdx > 0 && unsigned.substring(0, colonIdx).lowercase() in knownFields)
          val result =
            if (keepAsIs) {
              term
            } else {
              val prefix = if (term.length > 1 && (term.startsWith("+") || term.startsWith("-"))) term.substring(0, 1) else ""
              var core = term.substring(prefix.length)
              var suffix = ""
              while (core.length > 1 && (core.endsWith("*") || core.endsWith("?"))) {
                suffix = core.takeLast(1) + suffix
                core = core.dropLast(1)
              }
              prefix + QueryParser.escape(core) + suffix
            }
          restoreRangePlaceholders(result, protectedRanges)
        }
      }
  }

  private fun restoreRangePlaceholders(
    term: String,
    protectedRanges: Map<String, String>,
  ): String {
    var restored = term
    protectedRanges.forEach { (placeholder, range) -> restored = restored.replace(placeholder, range) }
    return restored
  }

  fun upgradeIndex() {
    IndexUpgrader(directory, IndexWriterConfig(indexAnalyzer), true).upgrade()
    logger.info { "Lucene index upgraded" }
  }

  fun addDocument(doc: Document) {
    indexWriter.addDocument(doc)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun addDocuments(docs: Iterable<Document>) {
    indexWriter.addDocuments(docs)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun updateDocument(
    term: Term,
    doc: Document,
  ) {
    indexWriter.updateDocument(term, doc)
    luceneCommitter.commitAndMaybeRefresh()
  }

  fun deleteDocuments(term: Term) {
    indexWriter.deleteDocuments(term)
    luceneCommitter.commitAndMaybeRefresh()
  }
}
