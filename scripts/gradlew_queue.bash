#!/usr/bin/env bash
# Gradle build queue — serializes builds so only one runs at a time.
#
# Linux helper (needs flock from util-linux). On Windows use
# scripts/gradlew_queue.ps1 instead; Git Bash is not supported.
#
# If a build is already in progress, your command is queued and runs
# automatically when it's your turn. FIFO is approximate (OS-level
# file-lock scheduling), which is fine for the typical 2-3 terminal case.
#
# All Gradle argument forms pass through unchanged:
#   ./scripts/gradlew_queue.bash :app:assembleDebug --stacktrace
#   ./scripts/gradlew_queue.bash clean build -Penv=prod
#   ./scripts/gradlew_queue.bash :lib:test --tests "com.example.MyTest"
#
# Lock file: .gradle/queue.lock     (flock; auto-released if the process dies)
# Info file: .gradle/queue.active   (tells queued instances what's running)
# Cleaned up on EXIT / INT / TERM, including Ctrl+C.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLEW="$PROJECT_ROOT/gradlew"
LOCK_DIR="$PROJECT_ROOT/.gradle"
LOCK_FILE="$LOCK_DIR/queue.lock"
INFO_FILE="$LOCK_DIR/queue.active"

# Color only when stderr is a terminal (status text goes to stderr so Gradle
# stdout stays clean if the caller redirects it).
if [[ -t 2 ]]; then
    C_RED=$'\033[31m'
    C_YELLOW=$'\033[33m'
    C_GREEN=$'\033[32m'
    C_GRAY=$'\033[90m'
    C_RESET=$'\033[0m'
else
    C_RED=
    C_YELLOW=
    C_GREEN=
    C_GRAY=
    C_RESET=
fi

if ! command -v flock >/dev/null 2>&1; then
    printf '%s  Error: flock is required (util-linux).%s\n' "$C_RED" "$C_RESET" >&2
    printf '%s  This script is for Linux. On Windows use scripts/gradlew_queue.ps1.%s\n' "$C_GRAY" "$C_RESET" >&2
    exit 1
fi

if [[ ! -f "$GRADLEW" ]]; then
    printf '%s  Error: gradlew not found in %s%s\n' "$C_RED" "$PROJECT_ROOT" "$C_RESET" >&2
    printf '%s  Expected the Gradle wrapper at the repo root (parent of scripts/).%s\n' "$C_GRAY" "$C_RESET" >&2
    exit 1
fi

mkdir -p "$LOCK_DIR"

# Pretty-print the command (same quoting rule as the PowerShell sibling).
if [[ $# -eq 0 ]]; then
    cmd_display='(no tasks)'
else
    parts=()
    for a in "$@"; do
        if [[ "$a" =~ [[:space:]] ]]; then
            parts+=("\"$a\"")
        else
            parts+=("$a")
        fi
    done
    cmd_display="${parts[*]}"
fi

lock_held=0
queued=0
start_s=0
tick=0
spinner='|/-\'

cleanup() {
    rm -f "$INFO_FILE" 2>/dev/null || true
    if [[ "$lock_held" -eq 1 ]]; then
        flock -u 200 2>/dev/null || true
        exec 200>&- 2>/dev/null || true
        lock_held=0
    fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ── Acquire exclusive lock (queue gate) ──────────────────────────────────
exec 200>"$LOCK_FILE"
while ! flock -n 200 2>/dev/null; do
    if [[ "$queued" -eq 0 ]]; then
        active=""
        if [[ -f "$INFO_FILE" ]]; then
            active="$(tr -d '\r' < "$INFO_FILE" 2>/dev/null || true)"
            active="${active#"${active%%[![:space:]]*}"}"
            active="${active%"${active##*[![:space:]]}"}"
        fi

        printf '\n' >&2
        printf '%s  Gradle is busy! Your command has been queued, please wait...%s\n' "$C_YELLOW" "$C_RESET" >&2
        if [[ -n "$active" ]]; then
            printf '%s  Running : %s%s\n' "$C_GRAY" "$active" "$C_RESET" >&2
        fi
        printf '%s  Queued  : gradlew %s%s\n' "$C_YELLOW" "$cmd_display" "$C_RESET" >&2
        printf '\n' >&2

        queued=1
        start_s="$(date +%s)"
    fi

    ch="${spinner:$((tick % 4)):1}"
    secs=$(( $(date +%s) - start_s ))
    printf '\r%s  %s Waiting... (%ss)  %s' "$C_GRAY" "$ch" "$secs" "$C_RESET" >&2
    tick=$((tick + 1))
    sleep 0.25
done
lock_held=1

if [[ "$queued" -eq 1 ]]; then
    waited=$(( $(date +%s) - start_s ))
    printf '\r%s  * Queue cleared after %ss - running now!      %s\n\n' "$C_GREEN" "$waited" "$C_RESET" >&2
fi

# Write active-build info so queued instances can see what's running
printf 'gradlew %s\n' "$cmd_display" > "$INFO_FILE" || true

# ── Run Gradle ───────────────────────────────────────────────────────────
set +e
"$GRADLEW" "$@"
exit_code=$?
set -e

exit "$exit_code"
