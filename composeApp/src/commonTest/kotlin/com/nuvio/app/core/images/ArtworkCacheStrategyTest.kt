package com.nuvio.app.core.images

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkCacheStrategyTest {
    @Test
    fun artworkCdnsReuseTheDiskCopy() {
        assertTrue(isImmutableArtworkUrl("https://assets.fanart.tv/fanart/tv/81797/showbackground/one-piece-5a1b.jpg"))
        assertTrue(isImmutableArtworkUrl("https://image.tmdb.org/t/p/w1280/abc.jpg"))
        assertTrue(isImmutableArtworkUrl("https://ARTWORKS.thetvdb.com/banners/fanart/original/1.jpg"))
    }

    @Test
    fun everythingElseKeepsFollowingCacheControl() {
        assertFalse(isImmutableArtworkUrl("https://aiometadata.example/poster/tt1.jpg?rating=1"))
        assertFalse(isImmutableArtworkUrl("https://fanart.tv.evil.example/x.jpg"))
        assertFalse(isImmutableArtworkUrl("https://evil.example/assets.fanart.tv/x.jpg"))
        assertFalse(isImmutableArtworkUrl("not a url"))
    }
}
