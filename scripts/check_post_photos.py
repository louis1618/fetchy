"""Small offline check of the shipped CLI, with only remote responses mocked."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile

project = Path(__file__).resolve().parents[1]
sys.path.insert(0, sys.argv[1] if len(sys.argv) > 1 else str(project / 'android/app/src/main/assets/yt-dlp'))
assets = Path(sys.argv[2]) if len(sys.argv) > 2 else project / 'android/app/src/main/assets'
import yt_dlp
from yt_dlp.extractor.bluesky import BlueskyIE as BaseBlueskyIE
from yt_dlp.extractor.pinterest import PinterestIE as BasePinterestIE
from yt_dlp.extractor.reddit import RedditIE as BaseRedditIE
from yt_dlp.extractor.tiktok import TikTokIE as BaseTikTokIE

tiktok = {'id': '7508442021644782866', 'desc': 'Photo fixture', 'imagePost': {'images': [
    {'imageURL': {'urlList': [f'https://p16.tiktokcdn.com/original-{i}.jpg']},
     'displayImage': {'urlList': ['https://p16.tiktokcdn.com/poster.jpg']}, 'imageWidth': 1080, 'imageHeight': 1920}
    for i in (1, 2)]}, 'music': {'playUrl': 'https://p16.tiktokcdn.com/music.mp3'}}
BaseTikTokIE._extract_web_data_and_status = lambda *a, **k: (tiktok, 0)
pin = {'id': '123456789', 'title': 'Original pin', 'images': {'orig': {'url': 'https://i.pinimg.com/originals/a.jpg', 'width': 1080, 'height': 1920}, '236x': {'url': 'https://i.pinimg.com/poster.jpg'}}}
BasePinterestIE._call_api = lambda *a, **k: {'data': pin}
post = {'id': 'abc123', 'title': 'Gallery fixture', 'url': 'https://www.reddit.com/r/test/comments/abc123/',
        'gallery_data': {'items': [{'media_id': i} for i in ('one', 'two')]},
        'media_metadata': {i: {'status': 'valid', 'e': 'Image', 's': {'u': f'https://i.redd.it/{i}.jpg', 'x': 1080, 'y': 1920}, 'p': [{'u': 'https://preview.redd.it/poster.jpg'}]} for i in ('one', 'two')}}
BaseRedditIE._real_initialize = lambda *a: None
BaseRedditIE._get_cookies = lambda *a, **k: {'loid': object()}
BaseRedditIE._download_json = lambda *a, **k: [{'data': {'children': [{'data': post}]}}]
BaseBlueskyIE._extract_post = lambda *a: {'author': {'did': 'did:plc:fixture'}, 'record': {'text': 'Original blob', 'embed': {'images': [
    {'image': {'ref': {'$link': 'bafyfixture'}, 'mimeType': 'image/jpeg'}}]}},
    'embed': {'images': [{'thumb': 'https://cdn.bsky.app/poster.jpg'}]}}
BaseBlueskyIE._get_service_endpoint = lambda *a: 'https://pds.bsky.social'

with tempfile.TemporaryDirectory() as directory:
    target = Path(directory) / 'instagram/yt_dlp_plugins/extractor'
    target.mkdir(parents=True)
    for source, module in [('fetchy_instagram_story.py', 'fetchy_story.py'), ('fetchy_photos.py', 'fetchy_photos.py')]:
        shutil.copyfile(assets / source, target / module)
    results = {}
    for label, url in {
        'TikTok': 'https://www.tiktok.com/@fixture/photo/7508442021644782866',
        'Pinterest': 'https://www.pinterest.com/pin/123456789/',
        'Reddit': 'https://www.reddit.com/comments/abc123/',
        'Bluesky': 'https://bsky.app/profile/fixture.bsky.social/post/fixture',
    }.items():
        with contextlib.redirect_stdout(io.StringIO()) as output:
            try:
                yt_dlp.main(['--plugin-dirs', directory, '--ignore-no-formats-error', '--skip-download', '--quiet', '--yes-playlist', '--dump-single-json', url])
            except SystemExit as status:
                assert status.code in (0, None), label
        root = json.loads(output.getvalue())
        entries = root.get('entries') or [root]
        photos = [e for e in entries if e.get('fetchy_original_image')]
        assert len(photos) == (2 if label in ('TikTok', 'Reddit') else 1), (label, entries)
        assert all(len(e['formats']) == 1 and 'poster' not in e['formats'][0]['url'] for e in photos)
        if label == 'TikTok':
            assert len(entries) == 3 and entries[-1]['formats'][0]['vcodec'] == 'none'
        if label == 'Bluesky':
            assert '/xrpc/com.atproto.sync.getBlob?' in photos[0]['formats'][0]['url']
        results[label] = {'originalPhotos': len(photos), 'assets': len(entries)}
    options = yt_dlp.parse_options(['--plugin-dirs', directory, '--quiet']).ydl_opts
    with yt_dlp.YoutubeDL(options) as downloader:
        extractor = downloader.get_info_extractor('Pinterest')
        # An original-looking poster is not a photo when video metadata is present.
        assert not extractor._extract_video({**pin, 'videos': {'video_list': {}}}).get('fetchy_original_image')
        extractor = downloader.get_info_extractor('Instagram')
        photo = {'pk': '4002710559245555959', 'media_type': 1, 'image_versions2': {'candidates': [
            {'url': 'https://instagram.fixture.fna.fbcdn.net/original.jpg', 'width': 1080, 'height': 1920}]}}
        assert extractor._extract_product_media(photo)['fetchy_original_image']
        assert not extractor._extract_product_media({**photo, 'media_type': 2}).get('fetchy_original_image')
        results['Instagram'] = 'photo original retained; video poster excluded'
    print(json.dumps(results))
