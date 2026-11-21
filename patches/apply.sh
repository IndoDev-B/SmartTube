#!/bin/sh
# Applies our changes to MediaServiceCore. The submodule points at the upstream author's repo, so
# changes to it can't be pushed there: they live here as patches and the CI builds apply them.
# Run from the repo root after the submodules are checked out.
#
#   sh patches/apply.sh            a patch that no longer applies fails the build (dev builds)
#   sh patches/apply.sh --lenient  it only warns, and the build goes on without it (releases)
#
# A patch that doesn't apply as is gets a three-way merge, which goes through when SmartTube only
# changed other lines. With PLUS_SKIPPED set to a file path, the plain name of each patch left
# out is written there, one per line, so the release changelog can say what is missing.

# What each patch does, in the words the changelog uses.
describe() {
    case "$1" in
        fast-video-start) echo "Faster video start (anti-bot token reuse, plain links first)" ;;
        format-info-cache) echo "Format cache (preloaded channels start at once)" ;;
        visionos-first) echo "Fastest YouTube player asked first" ;;
        search-filters) echo "TV mode search filters" ;;
        *) echo "$1" ;;
    esac
}

for p in patches/MediaServiceCore/*.patch; do
    [ -e "$p" ] || continue
    name=$(basename "$p" .patch)

    if git -C MediaServiceCore apply --check "../$p" 2>/dev/null; then
        git -C MediaServiceCore apply "../$p" || exit 1
        echo "Applied $p"
        continue
    fi

    # The code as the patches before this one left it, to go back to if the merge clashes.
    git -C MediaServiceCore add -A
    snapshot=$(git -C MediaServiceCore stash create)

    if git -C MediaServiceCore apply --3way "../$p" >/dev/null 2>&1 \
            && [ -z "$(git -C MediaServiceCore diff --name-only --diff-filter=U)" ]; then
        echo "Applied $p by three-way merge"
    elif [ "$1" = "--lenient" ]; then
        git -C MediaServiceCore reset -q --hard
        git -C MediaServiceCore clean -qfd
        [ -n "$snapshot" ] && git -C MediaServiceCore stash apply -q "$snapshot"
        echo "::warning::$p clashes with this MediaServiceCore. Building without it."
        [ -n "$PLUS_SKIPPED" ] && describe "$name" >> "$PLUS_SKIPPED"
    else
        echo "::error::$p no longer applies to this MediaServiceCore. Update the patch."
        exit 1
    fi
done
