#!/usr/bin/env bash
# Lưu log/kết quả CI vào nhánh "ci-reports" (đọc được qua GitHub API/git, không cần tải artifact).
# Dùng: publish-report.sh <tên> <file hoặc thư mục>...   (cần GITHUB_TOKEN có quyền contents: write)
set -u
NAME="$1"; shift
REPO_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
DIR=$(mktemp -d)
if ! git clone -q --depth 1 --branch ci-reports "$REPO_URL" "$DIR" 2>/dev/null; then
  git init -q "$DIR"
  git -C "$DIR" checkout -q --orphan ci-reports
  git -C "$DIR" remote add origin "$REPO_URL"
fi
DEST="$DIR/${GITHUB_REF_NAME//\//_}/$NAME"
rm -rf "$DEST"; mkdir -p "$DEST"
for f in "$@"; do
  [ -e "$f" ] && cp -r "$f" "$DEST/" 2>/dev/null
done
find "$DEST" -type f \( -name "*.png" -o -name "*.jpg" -o -name "*.apk" -o -name "*.so" \) -delete
# file lớn: giữ phần cuối
find "$DEST" -type f -size +400k -exec sh -c 'tail -c 400000 "$1" > "$1.t" && mv "$1.t" "$1"' _ {} \;
echo "run=${GITHUB_RUN_ID} attempt=${GITHUB_RUN_ATTEMPT:-1} sha=${GITHUB_SHA} $(date -u +%FT%TZ)" > "$DEST/RUN.txt"
cd "$DIR"
git add -A
git -c user.name="ci-reports" -c user.email="ci@users.noreply.github.com" commit -q -m "report $NAME run ${GITHUB_RUN_ID}" || exit 0
for i in 1 2 3 4 5; do
  git push -q origin HEAD:ci-reports && exit 0
  sleep $((i * 3))
  git pull -q --rebase origin ci-reports || true
done
echo "publish failed (ignored)"
