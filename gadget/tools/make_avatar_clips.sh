#!/bin/sh
# Converts Muse's avatar clips (720x720 MP4, white background) into what the app
# bundles, background turned black: 256x256 animated WebPs for the watch face
# (the size it draws them at) and 320x320 H.264 MP4s for the talk screen
# (played by the watch's hardware video decoder), both 15 fps
# (BackgroundKey, the same code the watch runs when Muse sends its own clips).
#   gadget/tools/make_avatar_clips.sh CLIPS_DIR
# CLIPS_DIR holds connecting.mp4, waiting_for_subagents.mp4, making_something.mp4
# and milestone_level_up.mp4; output goes to app/src/main/res/raw/.
set -e
CLIPS=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$ROOT/app/src/main/res/raw
TMP=$(mktemp -d)
mkdir -p "$OUT"
for pair in connecting:lux_idle waiting_for_subagents:lux_working making_something:lux_making milestone_level_up:lux_milestone; do
    clip=${pair%%:*}; name=${pair##*:}
    mkdir -p "$TMP/$clip/in" "$TMP/$clip/out"
    ffmpeg -v error -y -i "$CLIPS/$clip.mp4" -vf fps=15,scale=320:320:flags=lanczos "$TMP/$clip/in/%04d.png"
    "$ROOT/gradlew" -q -p "$ROOT" :gadget:keyFrames --args="$TMP/$clip/in $TMP/$clip/out"
    # Face: 256 px WebP (decoded in software, so no bigger than drawn).
    ffmpeg -v error -y -framerate 15 -i "$TMP/$clip/out/%04d.png" -vf scale=256:256:flags=lanczos \
        -c:v libwebp_anim -lossless 0 -q:v 72 -loop 0 "$OUT/$name.webp"
    # Talk screen: H.264 for the hardware decoder (main profile, 4:2:0, no audio).
    ffmpeg -v error -y -framerate 15 -i "$TMP/$clip/out/%04d.png" -c:v libx264 -profile:v main \
        -level 3.0 -pix_fmt yuv420p -crf 23 -g 15 -an -movflags +faststart "$OUT/${name}_video.mp4"
    # First frame, for always-on and previews.
    [ "$name" = lux_idle ] && ffmpeg -v error -y -i "$TMP/$clip/out/0001.png" -c:v libwebp -q:v 80 "$OUT/lux_still.webp"
    echo "$name: webp $(wc -c < "$OUT/$name.webp") bytes, mp4 $(wc -c < "$OUT/${name}_video.mp4") bytes"
done
rm -rf "$TMP"
