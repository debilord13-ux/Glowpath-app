Now Card bundle
===============

Files:
- music_bridge.plugin

What this plugin does now:
- Intercepts one chat command: `.play`
- Sends the list of all playlists into chat: `.playlists`
- Sends the tracks of one playlist into chat: `.pl <number or name>`
- Can share the current config into chat from the chat action menu or with `.style`
- Can apply a shared config from a message context menu with `Применить конфиг Now Card` or by replying with `.usestyle`
- Reads current track metadata from `content://com.musicplayer.bridge/status`
- Reads current artwork from `content://com.musicplayer.bridge/cover/current`
- Reads playlists from `content://com.musicplayer.bridge/playlists`
- Reads playlist tracks from `content://com.musicplayer.bridge/playlists/{id}/songs`
- Builds the final card image inside the plugin
- Sends the generated card into the current chat as a photo

Why this version is simpler:
- The app only exposes metadata and cover art
- The plugin handles themes, caption styles and final image generation
- It is much easier to extend with more card settings than app-side bitmap rendering

Current card settings:
- command text
- share command
- reply-import command
- card theme
- color style
- layout
- font
- accent strength
- overlay strength
- glow strength
- blur background
- rounded cover
- cover shadow
- panel border
- text shadow
- NOW PLAYING label toggle
- title line count
- artist line count
- progress bar
- progress style: classic / wave / segments
- progress time labels
- smart album chip filter
- play/pause chip
- branding mode: off / text / badge
- branding position
- branding text
- caption template
- preset export/import files in `Download/Now Card Presets`
- in-chat config sharing via formatted preset messages with a hidden encoded block

What changed in 5.0.0:
- new `.playlists` command: sends a numbered list of all playlists (name, track count, total duration, pin mark)
- new `.pl <number|name>` command: sends the track list of one playlist (artist — title — duration)
- both commands are customizable in plugin settings (Плейлисты section)
- playlist data is read through the new bridge URIs:
  `content://com.musicplayer.bridge/playlists` and `content://com.musicplayer.bridge/playlists/{id}/songs`
- matching a playlist works by exact number, exact name (case-insensitive) and then by partial name
- long playlists are trimmed to the first 40 tracks with an «и ещё N треков» tail
- app-side (GlowPath 1.2 API): HTTP endpoints `GET /playlists`, `GET /playlists/{id}`,
  `GET /playlists/{id}/cover`, `GET /search?q=`, `POST /control/play_playlist?id=|name=`
- fixed playlist playback in the app itself: shuffle and repeat now stay inside the
  playlist queue instead of jumping to the whole library

What changed in 4.7.0:
- safer Cyrillic font fallbacks for Mono and Condensed styles
- new Aura card theme
- rebuilt Poster theme with a cleaner composition
- cleaner placeholder cover when there is no artwork
- denser layouts with stronger shadows, glows and panel styling
- app-style brand badge inside the card instead of awkward bottom text
- caption under the photo is now off by default
- file-based style presets you can export and import between users
- cleaner menu-based config sharing flow inside ExteraGram chats
- prettier shared preset messages with a hidden encoded block instead of a raw visible token line
- stronger accent-strength differences
- selectable progress styles including a wave-style slider
- preset import/export now normalizes missing values to avoid null settings crashes

Suggested usage:
- Start any track in the music app
- Open ExteraGram
- Type `.play` in a chat
- The plugin should cancel the text message and send a styled track card photo instead
- Type `.playlists` to receive the list of your playlists
- Type `.pl 1` or `.pl Road` to receive the tracks of one playlist

Notes:
- The final Android app package name must stay `com.musicplayer`
- If there is no active track, the plugin shows an error bulletin and sends nothing
