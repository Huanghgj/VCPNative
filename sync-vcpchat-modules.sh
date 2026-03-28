#!/usr/bin/env bash
# Sync VCPChat HTML modules into Android assets for WebView hosting.
# Run this when VCPChat source is updated.
#
# Asset layout (matching relative paths in module HTML):
#   vcpchat/
#   ├── bridge-shim.js
#   ├── modules/
#   │   ├── notes/       (from Notemodules/)
#   │   ├── memo/        (from Memomodules/)
#   │   ├── forum/       (from Forummodules/)
#   │   ├── canvas/      (from Canvasmodules/)
#   │   ├── translator/  (from Translatormodules/)
#   │   ├── dice/        (from Dicemodules/)
#   │   ├── flowlock/    (from Flowlockmodules/)
#   │   ├── voicechat/   (from Voicechatmodules/)
#   │   ├── themes/      (from Themesmodules/)
#   │   ├── modules/     ← shared JS (so ../modules/ from notes/ resolves here)
#   │   │   ├── renderer/
#   │   │   ├── emoticonManager.js
#   │   │   └── ...
#   │   ├── styles/      ← CSS themes/base (so ../styles/ resolves here)
#   │   ├── vendor/      ← third-party libs (so ../vendor/ resolves here)
#   │   └── style.css    ← root CSS (so ../style.css resolves here)
#   └── (duplicates at top level for any other resolution patterns)

set -euo pipefail

VCPCHAT="/root/VCPChat"
ASSETS="/root/VCPNative/android-app/app/src/main/assets/vcpchat"

# Clean previous (but keep bridge-shim.js)
rm -rf "$ASSETS/modules" "$ASSETS/styles" "$ASSETS/vendor" "$ASSETS/renderer" "$ASSETS/utils"
mkdir -p "$ASSETS/modules"

# ---- Module HTML/CSS/JS ----
declare -A MODULES=(
    ["Notemodules"]="notes"
    ["Memomodules"]="memo"
    ["Forummodules"]="forum"
    ["Canvasmodules"]="canvas"
    ["Translatormodules"]="translator"
    ["Dicemodules"]="dice"
    ["Flowlockmodules"]="flowlock"
    ["Voicechatmodules"]="voicechat"
    ["Themesmodules"]="themes"
)

for src_dir in "${!MODULES[@]}"; do
    dst="${MODULES[$src_dir]}"
    if [ -d "$VCPCHAT/$src_dir" ]; then
        echo "  Module: $src_dir → modules/$dst"
        cp -r "$VCPCHAT/$src_dir" "$ASSETS/modules/$dst"
    fi
done

# ---- Shared dependencies (resolve ../xxx from modules/yyy/) ----

# ../styles/ → modules/styles/
echo "  Shared: styles/"
cp -r "$VCPCHAT/styles" "$ASSETS/modules/styles"

# ../vendor/ → modules/vendor/
echo "  Shared: vendor/"
cp -r "$VCPCHAT/vendor" "$ASSETS/modules/vendor"

# ../style.css → modules/style.css
[ -f "$VCPCHAT/style.css" ] && cp "$VCPCHAT/style.css" "$ASSETS/modules/style.css"

# ../modules/*.js → modules/modules/*.js
echo "  Shared: modules/ (JS)"
mkdir -p "$ASSETS/modules/modules"
for f in "$VCPCHAT/modules/"*.js; do
    [ -f "$f" ] && cp "$f" "$ASSETS/modules/modules/"
done

# ../modules/renderer/ → modules/modules/renderer/
[ -d "$VCPCHAT/modules/renderer" ] && cp -r "$VCPCHAT/modules/renderer" "$ASSETS/modules/modules/renderer"
[ -d "$VCPCHAT/modules/utils" ] && cp -r "$VCPCHAT/modules/utils" "$ASSETS/modules/modules/utils"

# ---- Summary ----
echo ""
echo "Synced ${#MODULES[@]} modules"
TOTAL=$(du -sh "$ASSETS" | cut -f1)
echo "Total assets size: $TOTAL"
echo ""
echo "Module entry points:"
find "$ASSETS/modules" -maxdepth 2 -name "*.html" | sort | while read f; do
    echo "  ${f#$ASSETS/modules/}"
done
