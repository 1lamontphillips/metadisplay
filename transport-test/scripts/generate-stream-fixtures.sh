#!/usr/bin/env bash
set -euo pipefail
transport_project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_output_dir="${1:-$(mktemp -d /tmp/metadisplay-stream-fixtures.XXXXXX)}"
mkdir -p "$fixture_output_dir"
ffmpeg -hide_banner -loglevel error -nostdin -n -stream_loop -1 \
  -i "$transport_project_root/app/src/main/assets/sample.mp4" -t 20 -map 0:v:0 -an \
  -r 24 -c:v libx264 -profile:v high -level:v 1.2 -preset veryfast -pix_fmt yuv420p \
  -bf 0 -g 24 -keyint_min 24 -sc_threshold 0 -b:v 120k -maxrate 160k -bufsize 320k \
  -movflags +faststart "$fixture_output_dir/stream-control.mp4"
ffmpeg -hide_banner -loglevel error -nostdin -n \
  -i "$fixture_output_dir/stream-control.mp4" -map 0:v:0 -c copy \
  -movflags +empty_moov+default_base_moof+frag_keyframe+skip_trailer \
  -frag_duration 1000000 "$fixture_output_dir/fragmented.mp4"
printf 'Generated matching test fixtures in %s\n' "$fixture_output_dir"
