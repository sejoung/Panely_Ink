#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/release.sh patch [--skip-checks] [--push|--no-push]
  scripts/release.sh minor [--skip-checks] [--push|--no-push]
  scripts/release.sh major [--skip-checks] [--push|--no-push]
  scripts/release.sh v0.1.0 [--skip-checks] [--push|--no-push]

Modes:
  patch      0.1.0 -> 0.1.1
  minor      0.1.0 -> 0.2.0
  major      0.1.0 -> 1.0.0
  vX.Y.Z     release the current versionName; tag must match versionName

What it does:
  1. Verifies the working tree is clean.
  2. For patch/minor/major, bumps versionName and versionCode in app/build.gradle.kts.
  3. Runs release checks unless --skip-checks is set.
  4. Builds the unsigned release APK.
  5. Copies APK + SHA256SUMS.txt into build/release/<tag>/.
  6. For patch/minor/major, commits the version bump.
  7. Creates the local git tag.
  8. Asks whether to push the commit and tag unless --push or --no-push is set.

Examples:
  scripts/release.sh patch
  scripts/release.sh minor --push
  scripts/release.sh v0.1.0 --no-push
  scripts/release.sh patch --skip-checks

Notes:
  - Pushing a v* tag triggers .github/workflows/release.yml.
  - The current Android release build is unsigned.
EOF
}

die() {
  echo "release: $*" >&2
  exit 1
}

read_gradle_value() {
  local key="$1"
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\"\\{0,1\\}\\([^\"]*\\)\"\\{0,1\\}.*/\\1/p" app/build.gradle.kts |
    head -n 1
}

parse_semver() {
  local version="$1"
  [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "version must be X.Y.Z, got $version"
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
}

bump_version() {
  local mode="$1"
  parse_semver "$version_name"
  case "$mode" in
    patch)
      patch=$((patch + 1))
      ;;
    minor)
      minor=$((minor + 1))
      patch=0
      ;;
    major)
      major=$((major + 1))
      minor=0
      patch=0
      ;;
    *)
      die "unknown bump mode: $mode"
      ;;
  esac
  new_version_name="${major}.${minor}.${patch}"
}

prompt_push() {
  local answer
  printf "release: push commit and tag to origin now? [y/N] "
  read -r answer
  case "$answer" in
    y|Y|yes|YES)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

mode=""
skip_checks=false
push_mode="prompt"

while (($#)); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --push)
      push_mode="yes"
      ;;
    --no-push)
      push_mode="no"
      ;;
    --skip-checks)
      skip_checks=true
      ;;
    patch|minor|major|v*)
      if [[ -n "$mode" ]]; then
        die "release mode is already set to $mode"
      fi
      mode="$1"
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
  shift
done

[[ -n "$mode" ]] || die "missing release mode, for example patch or v0.1.0"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

[[ -x ./gradlew ]] || die "gradlew is not executable"

if [[ -n "$(git status --porcelain)" ]]; then
  git status --short
  die "working tree must be clean before releasing"
fi

version_name="$(read_gradle_value versionName)"
version_code="$(read_gradle_value versionCode)"
[[ -n "$version_name" ]] || die "could not read versionName from app/build.gradle.kts"
[[ "$version_code" =~ ^[0-9]+$ ]] || die "could not read numeric versionCode from app/build.gradle.kts"

new_version_name="$version_name"
new_version_code="$version_code"
should_commit_bump=false

case "$mode" in
  patch|minor|major)
    bump_version "$mode"
    new_version_code=$((version_code + 1))
    tag="v${new_version_name}"
    should_commit_bump=true
    ;;
  v*)
    tag="$mode"
    expected_tag="v${version_name}"
    if [[ "$tag" != "$expected_tag" ]]; then
      die "tag $tag does not match versionName $version_name; expected $expected_tag"
    fi
    ;;
esac

if git rev-parse "$tag" >/dev/null 2>&1; then
  die "tag $tag already exists"
fi

echo "release: current versionName=$version_name versionCode=$version_code"
echo "release: target  versionName=$new_version_name versionCode=$new_version_code tag=$tag"

if [[ "$should_commit_bump" == true ]]; then
  perl -0pi -e "s/versionCode\\s*=\\s*\\d+/versionCode = ${new_version_code}/" app/build.gradle.kts
  perl -0pi -e "s/versionName\\s*=\\s*\"[^\"]+\"/versionName = \"${new_version_name}\"/" app/build.gradle.kts
fi

if [[ "$skip_checks" == false ]]; then
  echo "release: running checks"
  ./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
else
  echo "release: skipping checks"
fi

echo "release: building unsigned release APK"
./gradlew assembleRelease

release_dir="build/release/$tag"
rm -rf "$release_dir"
mkdir -p "$release_dir"

shopt -s nullglob
apk_files=(app/build/outputs/apk/release/*.apk)
shopt -u nullglob
(( ${#apk_files[@]} > 0 )) || die "no release APK found"

for apk in "${apk_files[@]}"; do
  base="$(basename "$apk")"
  cp "$apk" "$release_dir/panely-ink-${tag}-${base}"
done

(
  cd "$release_dir"
  shasum -a 256 *.apk > SHA256SUMS.txt
)

if [[ "$should_commit_bump" == true ]]; then
  git add app/build.gradle.kts
  if ! git diff --cached --quiet; then
    echo "release: committing version bump"
    git commit -m "Release $tag"
  fi
fi

echo "release: creating local tag $tag"
git tag -a "$tag" -m "Panely Ink $tag"

should_push=false
case "$push_mode" in
  yes)
    should_push=true
    ;;
  no)
    should_push=false
    ;;
  prompt)
    if prompt_push; then
      should_push=true
    fi
    ;;
esac

if [[ "$should_push" == true ]]; then
  echo "release: pushing current branch and tag $tag"
  current_branch="$(git branch --show-current)"
  [[ -n "$current_branch" ]] || die "cannot push from a detached HEAD"
  git push origin "HEAD:${current_branch}"
  git push origin "$tag"
else
  echo "release: not pushed"
  echo "release: run 'git push origin HEAD:\$(git branch --show-current) && git push origin $tag' to trigger GitHub Release"
fi

echo "release: files written to $release_dir"
ls -l "$release_dir"
