#!/usr/bin/env bash
# Checks a deployed web app: check-web.sh <web-url> <api-url>
#   1. a deep link opens the app, instead of a 404 from the host
#   2. the app was built to call this API
#   3. the API accepts browser requests from this web app, and from no other origin (CORS)
set -euo pipefail

web=${1%/}
api=${2%/}
get() { curl -fsS --retry 5 --retry-all-errors --retry-delay 3 --max-time 60 "$@"; }
fail() {
  echo "::error::$1"
  exit 1
}

page=$(get "$web/manage/a-deep-link?tab=rooms")
grep -q 'id="root"' <<< "$page" || fail "A deep link on $web does not open the app."

script=$(grep -o '/assets/[^"]*\.js' <<< "$page" | head -n 1 || true)
[ -n "$script" ] || fail "No script found in the page at $web."
bundle=$(get "$web$script")
grep -qF "$api" <<< "$bundle" || fail "The web app at $web was not built for $api."

# The origin the API allows for a browser request coming from $1, or nothing.
allowed() {
  curl -sS --max-time 120 -o /dev/null -D - -X OPTIONS "$api/api/v1/facilities" \
    -H "Origin: $1" -H 'Access-Control-Request-Method: GET' |
    tr -d '\r' | awk 'tolower($1) == "access-control-allow-origin:" { print $2 }'
}
[ "$(allowed "$web")" = "$web" ] || fail "$api does not allow $web. Set CORS_ALLOWED_ORIGINS on the API."
[ -z "$(allowed https://not-slotted.example)" ] || fail "$api allows origins other than $web."

echo "$web opens deep links, calls $api and is the only allowed origin tried."
