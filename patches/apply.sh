#!/bin/sh
# Applies our changes to MediaServiceCore. The submodule points at the upstream author's repo, so
# changes to it can't be pushed there: they live here as patches and the CI builds apply them.
# Run from the repo root after the submodules are checked out.
#
#   sh patches/apply.sh            a patch that no longer applies fails the build (dev builds)
#   sh patches/apply.sh --lenient  it only warns, and the build goes on without it (releases)

for p in patches/MediaServiceCore/*.patch; do
    [ -e "$p" ] || continue

    if git -C MediaServiceCore apply --check "../$p" 2>/dev/null; then
        git -C MediaServiceCore apply "../$p" || exit 1
        echo "Applied $p"
    elif [ "$1" = "--lenient" ]; then
        echo "::warning::$p no longer applies to this MediaServiceCore. Building without it."
    else
        echo "::error::$p no longer applies to this MediaServiceCore. Update the patch."
        exit 1
    fi
done
