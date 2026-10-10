"""Original photos from one post. Never infer downloadable media from OG/poster images."""
from urllib.parse import urlencode, urlparse

from yt_dlp.extractor.bluesky import BlueskyIE as BaseBlueskyIE
from yt_dlp.extractor.pinterest import PinterestIE as BasePinterestIE
from yt_dlp.extractor.reddit import RedditIE as BaseRedditIE
from yt_dlp.extractor.tiktok import TikTokIE as BaseTikTokIE
from yt_dlp.utils import ExtractorError, determine_ext, mimetype2ext, unescapeHTML, variadic
from yt_dlp.utils.traversal import traverse_obj


def original_photo(identifier, url, width=None, height=None, title=None, ext=None):
    if not isinstance(url, str) or urlparse(url).scheme != 'https':
        return None
    return {
        'id': str(identifier), 'title': title or str(identifier), 'thumbnail': url,
        'fetchy_original_image': True,
        'formats': [{'format_id': 'fetchy-image', 'url': url, 'width': width, 'height': height,
                     'ext': ext or determine_ext(url, default_ext='jpg'), 'vcodec': 'none', 'acodec': 'none'}],
    }


class TikTokIE(BaseTikTokIE, plugin_name='fetchy-photo-posts'):
    def _parse_aweme_video_web(self, data, webpage_url, video_id, extract_flat=False):
        photos = traverse_obj(data, ('imagePost', 'images', ..., {dict}))
        if not photos or extract_flat:
            return BaseTikTokIE._parse_aweme_video_web(self, data, webpage_url, video_id, extract_flat)
        entries = []
        for index, photo in enumerate(photos):
            urls = traverse_obj(photo, ('imageURL', 'urlList', ..., {str}))
            if urls:
                # displayImage/thumbnail fields are deliberately ignored.
                entry = original_photo(f'{video_id}-photo-{index + 1}', urls[0], photo.get('imageWidth'),
                                       photo.get('imageHeight'), data.get('desc'), ext='jpg')
                if entry:
                    entry['http_headers'] = {'Referer': webpage_url}
                    entries.append(entry)
        music = traverse_obj(data, ('music', 'playUrl', {str}))
        if music:
            entries.append({'id': f'{video_id}-audio', 'title': data.get('desc') or video_id,
                            'formats': [{'format_id': 'photo-audio', 'url': music,
                                         'ext': determine_ext(music, default_ext='mp3'), 'vcodec': 'none', 'acodec': 'mp3'}],
                            'http_headers': {'Referer': webpage_url}})
        if not entries:
            raise ExtractorError('No original photos found in this post', expected=True)
        return self.playlist_result(entries, video_id, data.get('desc'))


class TikTokPhotoIE(BaseTikTokIE):
    # A separate route is necessary: the upstream lazy extractor retains its video-only regex.
    _VALID_URL = r'https?://(?:www\.)?tiktok\.com/@(?P<user_id>[\w.-]+)/photo/(?P<id>\d+)'
    _parse_aweme_video_web = TikTokIE._parse_aweme_video_web


class PinterestIE(BasePinterestIE, plugin_name='fetchy-original-pins'):
    def _extract_video(self, data, extract_formats=True):
        info = super()._extract_video(data, extract_formats)
        if info.get('formats') or info.get('_type') or data.get('videos') or data.get('story_pin_data'):
            return info
        image = traverse_obj(data, ('images', 'orig', {dict}))
        if image and (photo := original_photo(data['id'], image.get('url'), image.get('width'), image.get('height'), info.get('title'))):
            info.update(photo)
        return info


class BlueskyIE(BaseBlueskyIE, plugin_name='fetchy-post-photos'):
    def _extract_videos(self, root, video_id, embed_path='embed', record_path='record', record_subpath='embed'):
        entries = super()._extract_videos(root, video_id, embed_path, record_path, record_subpath)
        embed_path = variadic(embed_path, (str, bytes, dict, set))
        record_path = variadic(record_path, (str, bytes, dict, set))
        record_subpath = variadic(record_subpath, (str, bytes, dict, set))
        images = traverse_obj(root, (*record_path, *record_subpath, 'images', ..., {dict}))
        did = traverse_obj(root, ('author', 'did', {str}))
        if not images or not did:
            return entries
        endpoint = self._get_service_endpoint(did, video_id)
        title = traverse_obj(root, (*record_path, 'text', {str}))
        for image in images:
            cid = traverse_obj(image, ('image', 'ref', '$link', {str}))
            ext = traverse_obj(image, ('image', 'mimeType', {mimetype2ext}))
            if not cid or ext not in ('jpg', 'jpeg', 'png', 'webp', 'gif', 'avif'):
                continue
            uri = self._BLOB_URL_TMPL.format(endpoint) + '?' + urlencode({'did': did, 'cid': cid})
            photo = original_photo(f'{video_id}-{cid}', uri, title=title, ext=ext)
            if photo:
                entries.append(photo)
        return entries


class RedditIE(BaseRedditIE, plugin_name='fetchy-post-photos'):
    def _download_json(self, *args, **kwargs):
        result = super()._download_json(*args, **kwargs)
        if isinstance(result, list):
            self._fetchy_post = traverse_obj(result, (0, 'data', 'children', 0, 'data', {dict}))
        return result

    def _real_extract(self, url):
        self._fetchy_post = None
        try:
            info = super()._real_extract(url)
        except ExtractorError:
            if not self._photos():
                raise
            info = None
        photos = self._photos()
        if not photos:
            return info
        if info and info.get('formats'):
            photos.insert(0, info)
        elif info and info.get('_type') == 'playlist':
            photos.extend(info.get('entries') or [])
        return self.playlist_result(photos, self._fetchy_post['id'], self._fetchy_post.get('title'))

    def _photos(self):
        data = self._fetchy_post or {}
        entries = []
        for item in traverse_obj(data, ('gallery_data', 'items', ..., {dict})):
            key = item.get('media_id')
            media = (data.get('media_metadata') or {}).get(key) or {}
            if media.get('status') != 'valid' or media.get('e') not in ('Image', 'AnimatedImage'):
                continue
            source = media.get('s') or {}
            url = source.get('gif') if media.get('e') == 'AnimatedImage' else source.get('u')
            if url and (entry := original_photo(key, unescapeHTML(url), source.get('x'), source.get('y'), data.get('title'))):
                entries.append(entry)
        if not entries and data.get('post_hint') == 'image' and urlparse(data.get('url') or '').hostname == 'i.redd.it':
            if entry := original_photo(data['id'], data['url'], title=data.get('title')):
                entries.append(entry)
        return entries
