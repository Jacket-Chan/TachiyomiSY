package exh.debug

import android.app.Application
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonParser
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.SourceManager.Companion.currentDelegatedSources
import exh.EH_SOURCE_ID
import exh.EXHMigrations
import exh.EXHSavedSearch
import exh.EXH_SOURCE_ID
import exh.eh.EHentaiThrottleManager
import exh.eh.EHentaiUpdateWorker
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.metadata.base.insertFlatMetadata
import exh.util.await
import exh.util.cancellable
import exh.util.jobScheduler
import java.lang.RuntimeException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.ts.api.http.serializer.FilterSerializer

object DebugFunctions {
    val app: Application by injectLazy()
    val db: DatabaseHelper by injectLazy()
    val prefs: PreferencesHelper by injectLazy()
    val sourceManager: SourceManager by injectLazy()

    fun forceUpgradeMigration() {
        prefs.eh_lastVersionCode().set(1)
        EXHMigrations.upgrade(prefs)
    }

    fun resetAgedFlagInEXHManga() {
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    return@mapNotNull null
                }
                manga
            }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // remove age flag
                    meta.aged = false
                    db.insertFlatMetadata(meta.flatten()).await()
                }
            }
        }
    }
    private val throttleManager = EHentaiThrottleManager()

    fun getDelegatedSourceList(): String = currentDelegatedSources.map { it.value.sourceName + " : " + it.value.sourceId + " : " + it.value.factory }.joinToString(separator = "\n")

    fun resetEHGalleriesForUpdater() {
        throttleManager.resetThrottle()
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    return@mapNotNull null
                }
                manga
            }.toList()
            val eh = sourceManager.getOrStub(EH_SOURCE_ID)
            val ex = sourceManager.getOrStub(EXH_SOURCE_ID)

            for (manga in allManga) {
                throttleManager.throttle()
                if (manga.source == EH_SOURCE_ID) {
                    eh.fetchMangaDetails(manga).map { networkManga ->
                        manga.copyFrom(networkManga)
                        manga.initialized = true
                        db.insertManga(manga).executeAsBlocking()
                    }
                } else if (manga.source == EXH_SOURCE_ID) {
                    ex.fetchMangaDetails(manga).map { networkManga ->
                        manga.copyFrom(networkManga)
                        manga.initialized = true
                        db.insertManga(manga).executeAsBlocking()
                    }
                }
            }
        }
    }

    fun getEHMangaListWithAgedFlagInfo(): String {
        val galleries = mutableListOf(String())
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    return@mapNotNull null
                }
                manga
            }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // remove age flag
                    galleries += "Aged: ${meta.aged}\t Title: ${manga.title}"
                }
            }
        }
        return galleries.joinToString(",\n")
    }

    fun countAgedFlagInEXHManga(): Int {
        var agedAmount = 0
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    return@mapNotNull null
                }
                manga
            }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null && meta.aged) {
                    // remove age flag
                    agedAmount++
                }
            }
        }
        return agedAmount
    }

    fun addAllMangaInDatabaseToLibrary() {
        db.inTransaction {
            db.lowLevel().executeSQL(
                RawQuery.builder()
                    .query(
                        """
                        UPDATE ${MangaTable.TABLE}
                            SET ${MangaTable.COL_FAVORITE} = 1
                        """.trimIndent()
                    )
                    .affectsTables(MangaTable.TABLE)
                    .build()
            )
        }
    }

    fun countMangaInDatabaseInLibrary() = db.getMangas().executeAsBlocking().count { it.favorite }

    fun countMangaInDatabaseNotInLibrary() = db.getMangas().executeAsBlocking().count { !it.favorite }

    fun countMangaInDatabase() = db.getMangas().executeAsBlocking().size

    fun countMetadataInDatabase() = db.getSearchMetadata().executeAsBlocking().size

    fun countMangaInLibraryWithMissingMetadata() = db.getMangas().executeAsBlocking().count {
        it.favorite && db.getSearchMetadataForManga(it.id!!).executeAsBlocking() == null
    }

    fun clearSavedSearches() = prefs.eh_savedSearches().set(emptySet())

    fun listAllSources() = sourceManager.getCatalogueSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listAllSourcesClassName() = sourceManager.getCatalogueSources().joinToString("\n") {
        "${it::class.qualifiedName}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listVisibleSources() = sourceManager.getVisibleCatalogueSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listAllHttpSources() = sourceManager.getOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }
    fun listVisibleHttpSources() = sourceManager.getVisibleOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun convertAllEhentaiGalleriesToExhentai() = convertSources(EH_SOURCE_ID, EXH_SOURCE_ID)

    fun convertAllExhentaiGalleriesToEhentai() = convertSources(EXH_SOURCE_ID, EH_SOURCE_ID)

    fun testLaunchEhentaiBackgroundUpdater(): String {
        return EHentaiUpdateWorker.launchBackgroundTest(app)
    }

    fun rescheduleEhentaiBackgroundUpdater() {
        EHentaiUpdateWorker.scheduleBackground(app)
    }

    fun listScheduledJobs() = app.jobScheduler.allPendingJobs.map { j ->
        """
        {
            info: ${j.id},
            isPeriod: ${j.isPeriodic},
            isPersisted: ${j.isPersisted},
            intervalMillis: ${j.intervalMillis},
        }
        """.trimIndent()
    }.joinToString(",\n")

    fun cancelAllScheduledJobs() = app.jobScheduler.cancelAll()

    private fun convertSources(from: Long, to: Long) {
        db.lowLevel().executeSQL(
            RawQuery.builder()
                .query(
                    """
                    UPDATE ${MangaTable.TABLE}
                        SET ${MangaTable.COL_SOURCE} = $to
                        WHERE ${MangaTable.COL_SOURCE} = $from
                    """.trimIndent()
                )
                .affectsTables(MangaTable.TABLE)
                .build()
        )
    }

    fun copyEHentaiSavedSearchesToExhentai() {
        runBlocking {
            val filterSerializer = FilterSerializer()
            val source = sourceManager.getOrStub(EH_SOURCE_ID) as CatalogueSource
            val newSource = sourceManager.getOrStub(EXH_SOURCE_ID) as CatalogueSource
            val savedSearches = prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != source.id) return@mapNotNull null
                    val content = JsonParser.parseString(it.substringAfter(':')).obj

                    val originalFilters = source.getFilterList()
                    filterSerializer.deserialize(originalFilters, content["filters"].array)
                    EXHSavedSearch(
                        content["name"].string,
                        content["query"].string,
                        originalFilters
                    )
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.toMutableList()
            savedSearches += prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != newSource.id) return@mapNotNull null
                    val content = JsonParser.parseString(it.substringAfter(':')).obj

                    val originalFilters = source.getFilterList()
                    filterSerializer.deserialize(originalFilters, content["filters"].array)
                    EXHSavedSearch(
                        content["name"].string,
                        content["query"].string,
                        originalFilters
                    )
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.filterNot { newSavedSearch -> savedSearches.any { it.name == newSavedSearch.name } }

            val otherSerialized = prefs.eh_savedSearches().get().filter {
                !it.startsWith("${newSource.id}:")
            }
            val newSerialized = savedSearches.map {
                "${newSource.id}:" + jsonObject(
                    "name" to it.name,
                    "query" to it.query,
                    "filters" to filterSerializer.serialize(it.filterList)
                ).toString()
            }
            prefs.eh_savedSearches().set((otherSerialized + newSerialized).toSet())
        }
    }

    fun copyExhentaiSavedSearchesToEHentai() {
        runBlocking {
            val filterSerializer = FilterSerializer()
            val source = sourceManager.getOrStub(EXH_SOURCE_ID) as CatalogueSource
            val newSource = sourceManager.getOrStub(EH_SOURCE_ID) as CatalogueSource
            val savedSearches = prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != source.id) return@mapNotNull null
                    val content = JsonParser.parseString(it.substringAfter(':')).obj

                    val originalFilters = source.getFilterList()
                    filterSerializer.deserialize(originalFilters, content["filters"].array)
                    EXHSavedSearch(
                        content["name"].string,
                        content["query"].string,
                        originalFilters
                    )
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.toMutableList()
            savedSearches += prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != newSource.id) return@mapNotNull null
                    val content = JsonParser.parseString(it.substringAfter(':')).obj

                    val originalFilters = source.getFilterList()
                    filterSerializer.deserialize(originalFilters, content["filters"].array)
                    EXHSavedSearch(
                        content["name"].string,
                        content["query"].string,
                        originalFilters
                    )
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.filterNot { newSavedSearch -> savedSearches.any { it.name == newSavedSearch.name } }

            val otherSerialized = prefs.eh_savedSearches().get().filter {
                !it.startsWith("${newSource.id}:")
            }
            val newSerialized = savedSearches.map {
                "${newSource.id}:" + jsonObject(
                    "name" to it.name,
                    "query" to it.query,
                    "filters" to filterSerializer.serialize(it.filterList)
                ).toString()
            }
            prefs.eh_savedSearches().set((otherSerialized + newSerialized).toSet())
        }
    }
}
