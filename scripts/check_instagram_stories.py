"""Offline check using Fetchy's actual bundled extractor and photo override."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile

project = Path(__file__).resolve().parents[1]
sys.path.insert(0, sys.argv[1] if len(sys.argv) > 1 else str(project / 'android/app/src/main/assets/yt-dlp'))
plugin_source = Path(sys.argv[2]) if len(sys.argv) > 2 else project / 'android/app/src/main/assets/fetchy_instagram_story.py'
import yt_dlp
from yt_dlp.extractor.instagram import InstagramStoryIE as BaseStoryIE, _pk_to_id

photo_pk, video_pk = '4002710559245555959', '4002710559245555960'
photos = [{'url': f'https://instagram.fixture.fna.fbcdn.net/{size}.jpg', 'width': size, 'height': size} for size in (320, 1080)]
items = [
    {'pk': photo_pk, 'media_type': 1, 'image_versions2': {'candidates': photos}},
    {'pk': video_pk, 'media_type': 2, 'video_codec': 'h264', 'has_audio': True, 'video_duration': 2.0,
     'video_versions': [{'id': 'v1', 'url': 'https://instagram.fixture.fna.fbcdn.net/original.mp4', 'width': 720, 'height': 1280}],
     'image_versions2': {'candidates': photos}},
]
# Mock only remote responses; loading and extraction use the actual shipped CLI.
BaseStoryIE._can_impersonate = False
BaseStoryIE._download_webpage = lambda *a, **k: '{"user":{"pk":"42","username":"fixture"}}'
BaseStoryIE._download_json = lambda *a, **k: {'reels': {'42': {'user': {'pk': '42', 'username': 'fixture'}, 'items': items}}}

with tempfile.TemporaryDirectory() as directory:
    plugin = Path(directory) / 'instagram/yt_dlp_plugins/extractor/fetchy_story.py'
    plugin.parent.mkdir(parents=True)
    shutil.copyfile(plugin_source, plugin)
    options = yt_dlp.parse_options(['--plugin-dirs', directory, '--ignore-no-formats-error', '--skip-download', '--quiet']).ydl_opts
    # Execute the real CLI setup, including its plugin loader, before the mock API.
    with contextlib.redirect_stdout(io.StringIO()) as cli_output:
        try:
            yt_dlp.main(['--plugin-dirs', directory, '--ignore-no-formats-error', '--skip-download', '--quiet', '--no-playlist', '--dump-single-json', f'https://www.instagram.com/stories/fixture/{photo_pk}/'])
        except SystemExit as exit_status:
            assert exit_status.code in (0, None)
    cli_photo = json.loads(cli_output.getvalue())
    assert cli_photo['fetchy_story_image'] and cli_photo['id'] == _pk_to_id(photo_pk)
    options['noplaylist'] = True
    with yt_dlp.YoutubeDL(options) as downloader:
        extractor = downloader.get_info_extractor('InstagramStory')
        assert extractor.PLUGIN_NAME == 'fetchy-story-images'
        def extract(pk):
            return downloader.process_ie_result(extractor.extract(f'https://www.instagram.com/stories/fixture/{pk}/'), download=False)
        photo, video = extract(photo_pk), extract(video_pk)
        assert photo['id'] == _pk_to_id(photo_pk) and photo['fetchy_story_image'] is True
        assert len(photo['formats']) == 1 and photo['formats'][0]['url'].endswith('/1080.jpg')
        assert video['id'] == _pk_to_id(video_pk) and not video.get('fetchy_story_image')
        assert all(f['url'].endswith('.mp4') for f in video['formats'])
        downloader.params['noplaylist'] = False
        playlist = extractor.extract('https://www.instagram.com/stories/fixture/')
        assert len(playlist['entries']) == 2
        print(json.dumps({'cli': 'photo override loaded', 'photo': 'largest original', 'video': 'poster excluded', 'single': 'exact ID', 'account': 'both media retained'}))
