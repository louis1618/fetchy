"""Keep explicit photo stories that the bundled video extractor otherwise drops."""
from yt_dlp.extractor.instagram import InstagramStoryIE as BaseInstagramStoryIE, InstagramIE as BaseInstagramIE
from yt_dlp.utils import determine_ext


class InstagramStoryIE(BaseInstagramStoryIE, plugin_name='fetchy-story-images'):
    def _extract_product(self, product_info, *args, **kwargs):
        info = super()._extract_product(product_info, *args, **kwargs)
        # image_versions2 is original media only when the API says media_type=1.
        # Video posters never become downloadable photos.
        if product_info.get('media_type') == 1 and not info.get('formats'):
            photos = info.get('thumbnails') or []
            if photos:
                photo = max(photos, key=lambda p: (p.get('width') or 0) * (p.get('height') or 0))
                info['formats'] = [{
                    **photo, 'format_id': 'fetchy-story-image',
                    'ext': determine_ext(photo['url'], default_ext='jpg'),
                    'vcodec': 'none', 'acodec': 'none',
                }]
                info['fetchy_story_image'] = True
        return info


class InstagramIE(BaseInstagramIE, plugin_name='fetchy-post-images'):
    def _extract_product_media(self, media):
        info = super()._extract_product_media(media)
        if media.get('media_type') == 1 and not info.get('formats') and info.get('thumbnails'):
            photo = max(info['thumbnails'], key=lambda p: (p.get('width') or 0) * (p.get('height') or 0))
            info.update(fetchy_original_image=True, formats=[{
                **photo, 'format_id': 'fetchy-image', 'ext': determine_ext(photo['url'], default_ext='jpg'),
                'vcodec': 'none', 'acodec': 'none',
            }])
        return info
