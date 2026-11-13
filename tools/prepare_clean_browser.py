"""Package the user's TV interface and an explicit list of plain Android helpers.

The legacy Firefox activities, fragments, engines and resources are not compiled.
"""
from pathlib import Path
from collections import deque
import re
import shutil
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
out = root / 'app/build/generated/cleanBrowser'
if out.exists():
    shutil.rmtree(out)
res = out / 'res'
java = out / 'java'
res.mkdir(parents=True)
java.mkdir()
source = root / 'app/src/main'

helpers = [
    'utils/SmartAddress.java',
    'browser/cursor/TvCursorLayout.kt',
    'browser/cursor/RemoteCursorMotion.java',
    'browser/cursor/RemoteKeyRouter.java',
    'browser/FullscreenPointerGesture.java',
    'library/HistoryStore.kt',
    'library/DownloadsStore.kt',
    'library/DownloadStream.java',
    'diagnostics/CodecReport.java',
    'extensions/ExtensionsActivity.java',
    'extensions/ExtensionPermissions.java',
    'browser/BrowserSessionSupport.kt',
]
for name in helpers:
    original = source / 'java/org/mozilla/focus' / name
    text = original.read_text()
    if name.startswith('extensions/'):
        text = text.replace('import org.mozilla.focus.iwebview.WebViewProvider;',
                            'import org.mozilla.tv.browser.BrowserApp;')
        text = text.replace('WebViewProvider.getRuntime(', 'BrowserApp.getRuntime(')
        text = text.replace('import org.mozilla.focus.MainActivity;',
                            'import org.mozilla.tv.browser.BrowserActivity;')
        text = text.replace('MainActivity.class', 'BrowserActivity.class')
    if name == 'browser/BrowserSessionSupport.kt':
        text = text.replace('import org.mozilla.focus.iwebview.IWebView\n', '')
        text = text.replace('private val activity: AppCompatActivity\n',
                            'private val activity: AppCompatActivity,\n'
                            '    private val currentUrl: () -> String\n')
        start = text.index('    fun attach(')
        end = text.index('    private inner class Ticket(', start)
        text = text[:start] + '''    private fun title(session: GeckoSession): String {
        val host = Uri.parse(currentUrl()).host
        return if (host.isNullOrBlank()) "Website" else "Website — $host"
    }

''' + text[end:]
        text = text.replace('    private fun confirmDownload(', '    fun confirmDownload(')
        start = text.index('        views.keys.toList().forEach')
        end = text.index('        views.clear()', start) + len('        views.clear()')
        text = text[:start] + text[end:]
        text = text.replace('    private val views = WeakHashMap<GeckoSession, WeakReference<View>>()\n', '')
    target = java / 'org/mozilla/focus' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)

# Only package resources reachable from the TV interface and the selected icons.
values = {}
for path in sorted((source / 'res/values').glob('*.xml')):
    for item in ET.parse(path).getroot():
        kind = item.get('type') if item.tag == 'item' else item.tag
        if item.get('name'):
            values[(kind, item.get('name'))] = ET.tostring(item, encoding='unicode')
values[('string', 'preference_mozilla_telemetry2')] = (
    '<string name="preference_mozilla_telemetry2">Desktop sites</string>')
values[('color', 'photonBlue50')] = '<color name="photonBlue50">#0A84FF</color>'
values[('color', 'photonBlue70')] = '<color name="photonBlue70">#003EAA</color>'
values[('color', 'photonBlue60')] = '<color name="photonBlue60">#0060DF</color>'
values[('color', 'photonGrey90')] = '<color name="photonGrey90">#0C0C0D</color>'
queue = deque([
    ('layout', 'activity_main'), ('layout', 'fragment_settings'),
    ('drawable', 'background_home'),
    ('drawable', 'ic_about'), ('drawable', 'ic_lock'),
    ('string', 'app_name'), ('string', 'tv_home_title'),
    ('string', 'tv_saved_sites'), ('string', 'tv_saved_sites_hint'),
    ('string', 'tv_no_bookmarks'), ('string', 'tv_bookmark_saved'),
    ('string', 'tv_bookmarked'), ('string', 'tv_bookmark'),
    ('string', 'extensions_menu_title'), ('string', 'playback_diagnostics_title'),
])
seen = set()
selected = []
references = re.compile(r'@(?:\+)?(string|color|drawable|mipmap|dimen|style|font)/([\w.]+)')
while queue:
    kind, name = queue.popleft()
    if (kind, name) in seen:
        continue
    seen.add((kind, name))
    if (kind, name) in values:
        text = values[(kind, name)]
        selected.append(text)
    else:
        paths = list((source / 'res' / kind).glob(name + '.*'))
        if not paths:
            raise RuntimeError(f'TV interface resource missing: {kind}/{name}')
        path = paths[0]
        target = res / kind / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix != '.xml':
            shutil.copy2(path, target)
            continue
        text = path.read_text()
        if name == 'activity_main':
            text = text.replace('org.mozilla.focus.widget.InlineAutocompleteEditText',
                                'androidx.appcompat.widget.AppCompatAutoCompleteTextView')
        target.write_text(text)
    queue.extend(references.findall(text))

(res / 'values').mkdir()
(res / 'values/tv_interface.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?><resources>\n'
    + '\n'.join(selected) + '\n</resources>')
(res / 'values/theme.xml').write_text('''<resources>
<style name="BrowserTheme" parent="Theme.AppCompat.NoActionBar">
<item name="colorPrimary">#252830</item>
<item name="colorPrimaryDark">#252830</item>
<item name="colorAccent">#94C6FF</item>
<item name="android:statusBarColor">@android:color/transparent</item>
<item name="android:windowActionModeOverlay">true</item>
</style>
</resources>''')
(res / 'xml').mkdir()
(res / 'xml/provider_paths.xml').write_text('''<paths>
<files-path name="privateDownloads" path="downloads/" />
<external-files-path name="downloads" path="Download/" />
</paths>''')
print(f'Prepared TV interface: {len(seen)} resources, {len(helpers)} Android helpers.')
icon = source / 'res/mipmap-xxhdpi/ic_launcher.png'
(res / 'mipmap-xxhdpi').mkdir()
shutil.copy2(icon, res / 'mipmap-xxhdpi/ic_launcher.png')
