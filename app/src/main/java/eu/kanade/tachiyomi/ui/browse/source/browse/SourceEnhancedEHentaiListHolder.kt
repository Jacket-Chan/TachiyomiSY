package eu.kanade.tachiyomi.ui.browse.source.browse

import android.graphics.Color
import android.view.View
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.util.system.getResourceColor
import exh.metadata.EX_DATE_FORMAT
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.Companion.getLocaleSourceUtil
import java.util.Date
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.date_posted
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.genre
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.language
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.rating_bar
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.thumbnail
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.title
import kotlinx.android.synthetic.main.source_enhanced_ehentai_list_item.uploader

/**
 * Class used to hold the displayed data of a manga in the catalogue, like the cover or the title.
 * All the elements from the layout file "item_catalogue_list" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new catalogue holder.
 */
class SourceEnhancedEHentaiListHolder(private val view: View, adapter: FlexibleAdapter<*>) :
    SourceHolder(view, adapter) {

    private val favoriteColor = view.context.getResourceColor(R.attr.colorOnSurface, 0.38f)
    private val unfavoriteColor = view.context.getResourceColor(R.attr.colorOnSurface)

    /**
     * Method called from [CatalogueAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param manga the manga to bind.
     */
    override fun onSetValues(manga: Manga) {
        title.text = manga.title
        title.setTextColor(if (manga.favorite) favoriteColor else unfavoriteColor)

        // Set alpha of thumbnail.
        thumbnail.alpha = if (manga.favorite) 0.3f else 1.0f

        setImage(manga)
    }

    fun onSetMetadataValues(manga: Manga, metadata: RaisedSearchMetadata) {
        if (metadata !is EHentaiSearchMetadata) return

        if (metadata.uploader != null) {
            uploader.text = metadata.uploader
        }

        val pair = when (metadata.genre) {
            "doujinshi" -> Pair(SourceTagsUtil.DOUJINSHI_COLOR, R.string.doujinshi)
            "manga" -> Pair(SourceTagsUtil.MANGA_COLOR, R.string.manga)
            "artistcg" -> Pair(SourceTagsUtil.ARTIST_CG_COLOR, R.string.artist_cg)
            "gamecg" -> Pair(SourceTagsUtil.GAME_CG_COLOR, R.string.game_cg)
            "western" -> Pair(SourceTagsUtil.WESTERN_COLOR, R.string.western)
            "non-h" -> Pair(SourceTagsUtil.NON_H_COLOR, R.string.non_h)
            "imageset" -> Pair(SourceTagsUtil.IMAGE_SET_COLOR, R.string.image_set)
            "cosplay" -> Pair(SourceTagsUtil.COSPLAY_COLOR, R.string.cosplay)
            "asianporn" -> Pair(SourceTagsUtil.ASIAN_PORN_COLOR, R.string.asian_porn)
            "misc" -> Pair(SourceTagsUtil.MISC_COLOR, R.string.misc)
            else -> Pair("", 0)
        }

        if (pair.first.isNotBlank()) {
            genre.setBackgroundColor(Color.parseColor(pair.first))
            genre.text = view.context.getString(pair.second)
        } else genre.text = metadata.genre

        metadata.datePosted?.let { date_posted.text = EX_DATE_FORMAT.format(Date(it)) }

        metadata.averageRating?.let { rating_bar.rating = it.toFloat() }

        val locale = getLocaleSourceUtil(metadata.tags.firstOrNull { it.namespace == "language" }?.name)
        val pageCount = metadata.length

        language.text = if (locale != null && pageCount != null) {
            view.resources.getQuantityString(R.plurals.browse_language_and_pages, pageCount, pageCount, locale.toLanguageTag().toUpperCase())
        } else if (pageCount != null) {
            view.resources.getQuantityString(R.plurals.num_pages, pageCount, pageCount)
        } else locale?.toLanguageTag()?.toUpperCase()
    }

    override fun setImage(manga: Manga) {
        GlideApp.with(view.context).clear(thumbnail)

        if (!manga.thumbnail_url.isNullOrEmpty()) {
            val radius = view.context.resources.getDimensionPixelSize(R.dimen.card_radius)
            val requestOptions = RequestOptions().transform(CenterCrop(), RoundedCorners(radius))
            GlideApp.with(view.context)
                .load(manga.toMangaThumbnail())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .apply(requestOptions)
                .dontAnimate()
                .placeholder(android.R.color.transparent)
                .into(thumbnail)
        }
    }
}
