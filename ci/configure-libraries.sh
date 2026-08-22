#!/usr/bin/env bash
set -euo pipefail
# Navidrome's default library (ID 1) is created automatically at container startup, pinned to
# whatever the top-level image config calls "/music", and cannot be repointed at a different
# path or deleted:
#
#   PUT /api/library/1 {"path":"/some/other/path"}
#     -> 500 {"error":"validation error: path for library with ID 1 cannot be changed"}
#   DELETE /api/library/1
#     -> 500 {"error":"validation error: library with ID 1 cannot be deleted"}
#
# (Confirmed against the real, pinned deluan/navidrome:0.63.2 image — the two error strings
# above are quoted verbatim from it.)
# So the compose file mounts the two fixture subtrees at disjoint container paths (/music and
# /audiobooks — see navidrome.compose.yml's volumes comment) and this script uses Navidrome's
# native (non-Subsonic) REST API to rename library 1 to "Music" (path unchanged) and create a
# second library "Audiobooks" at /audiobooks, matching the names LiveContractTest looks for.
# Admin users see every library implicitly and cannot be given explicit per-library grants
# (PUT /api/user/{id}/library on an admin fails with "validation error: cannot manually assign
# libraries to admin users") — confirmed empirically — so no user/library association step is
# needed here; that only matters for the non-admin users Plan 2's setup flow creates.
#
# Auth here is the native API's JWT (POST /auth/login, then X-ND-Authorization: Bearer <token>)
# -- a different mechanism from the Subsonic token/password auth SubsonicClient and
# LiveContractTest use; native /api/library and /api/user endpoints require it.
#
# Run once, after the container reports healthy, before the getScanStatus poll.

BASE="http://localhost:4533"

login_json="$(curl -sf -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"testpass"}')"
token="$(echo "$login_json" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)"
admin_id="$(echo "$login_json" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"

if [ -z "$token" ] || [ -z "$admin_id" ]; then
  echo "Failed to log in to Navidrome's native API: $login_json" >&2
  exit 1
fi

auth_header="X-ND-Authorization: Bearer $token"

# Rename library 1; path is resent unchanged (omitting it is not required to be safe, but the
# validation error above showed the API compares the *new* path against the existing one, so
# sending the existing value back is the documented-safe way to change only the name).
curl -sf -X PUT "$BASE/api/library/1" -H "$auth_header" -H 'Content-Type: application/json' \
  -d '{"name":"Music","path":"/music"}' > /dev/null

audiobooks_id="$(curl -sf -X POST "$BASE/api/library" -H "$auth_header" -H 'Content-Type: application/json' \
  -d '{"name":"Audiobooks","path":"/audiobooks"}' | grep -o '"id":"\?[0-9]*"\?' | grep -o '[0-9]*')"

if [ -z "$audiobooks_id" ]; then
  echo "Failed to create the Audiobooks library" >&2
  exit 1
fi

# Library creation does not itself scan; trigger a full scan across both libraries now that
# they're both in place. Subsonic auth (u/p), not the native JWT above.
#
# Observed once under heavy host load (not reproduced in 3 immediate retries under normal load,
# but cheap enough to guard against unconditionally): the scan fired here can land before the
# just-created library is fully visible to the scanner, so it only covers library 1 (Music) and
# getScanStatus's count sticks at 3, never 4, no matter how long the caller polls afterwards —
# nothing re-triggers a second scan on its own. So this retries the scan itself (not just
# polling) up to 5 times, each waiting for the previous one to finish, until all 4 fixture files
# are accounted for.
Q="v=1.16.1&c=ci&f=json&u=admin&p=testpass"
for attempt in 1 2 3 4 5; do
  curl -sf "$BASE/rest/startScan.view?$Q&fullScan=true" > /dev/null

  for i in $(seq 1 30); do
    # `|| true` on the whole pipeline: under `set -euo pipefail`, a single flaky request here
    # (curl failing, or the response not matching the grep) would otherwise abort the entire
    # script immediately, never reaching the outer 5-attempt retry loop this is nested inside —
    # the one thing that loop exists for. A failed poll iteration should read the same as "not
    # done scanning yet" and simply try again next second, not crash the script outright.
    scanning="$(curl -sf "$BASE/rest/getScanStatus.view?$Q" 2>/dev/null \
      | grep -o '"scanning":[a-z]*' | cut -d: -f2 || true)"
    [ "$scanning" = "false" ] && break
    sleep 1
  done

  count="$(curl -sf "$BASE/rest/getScanStatus.view?$Q" | grep -o '"count":[0-9]*' | head -1 | cut -d: -f2)"
  [ "$count" = "4" ] && exit 0
  sleep 1
done

echo "Scan did not converge on 4 tracks after 5 attempts (last count: $count)" >&2
exit 1
