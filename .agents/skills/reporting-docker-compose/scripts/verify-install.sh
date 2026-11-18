#!/bin/sh
# Check a Docker Compose install of the BIRT reporting plugin: Fineract health, plugin JAR and
# BIRT libraries mounted, admin login, and a BIRT report rendered to PDF.
# Waits up to 10 minutes for Fineract to report UP. Exits 0 only if all checks pass,
# 1 if a check fails, 2 if the stack isn't running or curl is missing.
# Usage: verify-install.sh   (from any directory; it uses this repository's docker-compose.yml)
# No set -e: a failing check must still reach the report below.
[ -z "${ZSH_VERSION:-}" ] || emulate sh
set -u
cd "$(dirname "$0")/../../../.." || exit 2

BASE=https://localhost:8443/fineract-provider
WAIT_SECONDS=600
REPORT=Active_Loans_Details
# -1 means "all" for each filter; office 1 is the Head Office every new tenant has.
REPORT_QUERY="output-type=PDF&locale=en&R_branch=1&R_loanOfficer=-1&R_currencyId=-1&R_fundId=-1"
REPORT_QUERY="$REPORT_QUERY&R_loanProductId=-1&R_loanPurposeId=-1"
PDF=$(mktemp) || exit 2
trap 'rm -f "$PDF"' EXIT

# Self-signed certificate (-k); a proxy from the environment must not handle localhost.
local_curl() { curl -k --noproxy '*' -s -m 60 "$@"; }

# Print the response body, a newline, then the HTTP status code.
curl_with_code() { local_curl -w '\n%{http_code}' "$@"; }

# Overall health is UP only with HTTP 200: Spring Boot answers 503 when the top-level status is
# DOWN, even if a component inside the body reports "status":"UP".
health_up() {
  [ "${health##*"$NL"}" = 200 ] || return 1
  case "${health%"$NL"*}" in *'"status":"UP"'*) return 0 ;; *) return 1 ;; esac
}
NL='
'

command -v curl >/dev/null || { echo "error: curl is needed for the checks" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "error: the Docker daemon isn't reachable" >&2; exit 2; }
if [ -z "$(docker compose ps -q fineract 2>/dev/null)" ]; then
  echo "error: fineract isn't running; run 'docker compose up -d' in the repository root first" >&2
  exit 2
fi

health=""
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while :; do
  health=$(curl_with_code "$BASE/actuator/health")
  health_up && break
  [ "$(date +%s)" -ge "$deadline" ] && break
  # A container that exited won't come up; don't wait the full 10 minutes for it.
  [ -z "$(docker compose ps -aq --status exited fineract 2>/dev/null)" ] || break
  sleep 10
done

running=$(docker compose ps -q --status running fineract 2>/dev/null)
# Exit status, not output: ls's "No such file" message also contains the file name.
docker compose exec -T fineract test -f /app/birt/plugin/birt-plugin.jar 2>/dev/null; jar_rc=$?
libs=$(docker compose exec -T fineract sh -c 'ls /app/birt/plugin/libs | grep -c birt' 2>/dev/null)
admin=$(local_curl -X POST "$BASE/api/v1/authentication" \
  -H 'Content-Type: application/json' -H 'Fineract-Platform-TenantId: default' \
  -d '{"username":"mifos","password":"password"}')
# Fineract alone has no BIRT engine: it answers 503 "There is no ReportingProcessService
# registered" here. Only the plugin answers with a PDF.
# The first report after a start also loads the BIRT engine, so it gets a longer timeout.
meta=$(local_curl -m 180 -o "$PDF" -w '%{http_code} %{content_type}' -u mifos:password \
  -H 'Fineract-Platform-TenantId: default' "$BASE/api/v1/runreports/$REPORT?$REPORT_QUERY")
# A transfer cut off after the first bytes leaves a partial PDF; only curl's exit status shows it.
report_rc=$?
magic=$(head -c 4 "$PDF" 2>/dev/null)

failed=0
# Print a PASS or FAIL line and remember a failure. Usage: report <check> <ok:0|1> <detail>
report() {
  if [ "$2" -eq 0 ]; then echo "PASS  $1: $3"; else echo "FAIL  $1: $3"; failed=1; fi
}
if health_up; then
  report "Fineract health" 0 "UP"
else
  body=$(printf '%s' "${health%"$NL"*}" | head -c 200)
  report "Fineract health" 1 "not UP (HTTP ${health##*"$NL"}: ${body:-no response})"
fi
if [ -z "$running" ]; then
  report "Plugin mounted" 1 "can't check: the fineract container isn't running (see the log below)"
elif [ "$jar_rc" -eq 0 ] && [ "${libs:-0}" -gt 0 ]; then
  report "Plugin mounted" 0 "/app/birt/plugin/birt-plugin.jar and $libs BIRT libraries"
else
  report "Plugin mounted" 1 "plugin JAR or BIRT libraries missing in /app/birt/plugin"
fi
login="Admin login mifos/default tenant"
case "$admin" in
  *'"authenticated":true'*) report "$login" 0 '"authenticated":true' ;;
  *) report "$login" 1 "$(printf '%s' "${admin:-no response}" | head -c 200)" ;;
esac
probe="GET /api/v1/runreports/$REPORT (PDF)"
case "$report_rc:$meta:$magic" in
  "0:200 application/pdf"*:%PDF)
    report "BIRT report" 0 "$probe -> 200, $(wc -c < "$PDF" | tr -d ' ') byte PDF" ;;
  *) report "BIRT report" 1 "$probe -> ${meta:-no response} (curl exit $report_rc):\
 $(head -c 200 "$PDF" 2>/dev/null | LC_ALL=C tr -d '\0')" ;;
esac

if [ $failed -ne 0 ]; then
  echo
  echo "Last 50 lines of the fineract log:"
  docker compose logs --tail=50 fineract
fi
exit $failed
