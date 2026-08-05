#!/usr/bin/env bash
# Builds the three MKVs the bench serves. They differ only in how their
# tracks are tagged, which is the whole point: the smart default is a
# decision about metadata, not about pixels.
set -euo pipefail
cd "$(dirname "$0")"

common=(-f lavfi -i "testsrc=size=1280x720:rate=24:duration=40")
enc=(-c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -c:s srt)

# 1. English audio, forced EN + full FR -> full FR expected (foreign audio)
ffmpeg -y "${common[@]}" -f lavfi -i "sine=frequency=440:duration=40" \
  -i forced_en.srt -i full_fr.srt -map 0:v -map 1:a -map 2 -map 3 "${enc[@]}" \
  -metadata:s:a:0 language=eng -disposition:a:0 default \
  -metadata:s:s:0 language=eng -disposition:s:0 forced \
  -metadata:s:s:1 language=fre english_audio.mkv

# 2. French audio, forced EN + full FR -> nothing expected: that forced
#    track is for English speakers watching the French version
ffmpeg -y "${common[@]}" -f lavfi -i "sine=frequency=660:duration=40" \
  -i forced_en.srt -i full_fr.srt -map 0:v -map 1:a -map 2 -map 3 "${enc[@]}" \
  -metadata:s:a:0 language=fra -disposition:a:0 default \
  -metadata:s:s:0 language=eng -disposition:s:0 forced \
  -metadata:s:s:1 language=fre french_audio.mkv

# 3. French audio, forced FR + full FR -> forced FR expected
ffmpeg -y "${common[@]}" -f lavfi -i "sine=frequency=880:duration=40" \
  -i forced_fr.srt -i full_fr.srt -map 0:v -map 1:a -map 2 -map 3 "${enc[@]}" \
  -metadata:s:a:0 language=fra -disposition:a:0 default \
  -metadata:s:s:0 language=fre -disposition:s:0 forced \
  -metadata:s:s:1 language=fre french_audio_forced_fr.mkv

echo "Fixtures built. Now: python3 fake_jellyfin.py 8097"
