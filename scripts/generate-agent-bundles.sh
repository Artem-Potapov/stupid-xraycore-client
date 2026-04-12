#!/usr/bin/env bash
# =============================================================================
# generate_bundle.sh
# Generates a stripped, annotated single-file codebase bundle for FileSearcher.
# Respects .gitignore at every directory level using `git check-ignore`.
#
# Usage:
#   ./generate_bundle.sh [PROJECT_ROOT] [OUTPUT_FILE]
#
# Defaults:
#   PROJECT_ROOT = current directory
#   OUTPUT_FILE  = CODEBASE_CACHE_FOR_AGENTS/codebase_bundle.txt
#
# Requirements:
#   - git (for .gitignore awareness)
#   - bash 4+
# =============================================================================

set -euo pipefail

# ── Args & defaults ──────────────────────────────────────────────────────────
PROJECT_ROOT="${1:-$(pwd)}"
OUTPUT_FILE="${2:-$PROJECT_ROOT/CODEBASE_CACHE_FOR_AGENTS/codebase_bundle.txt}"

# ── Config ───────────────────────────────────────────────────────────────────
# File extensions to include in the bundle
INCLUDE_EXTENSIONS=("kt" "kts" "xml" "json" "toml" "properties")

# Directories to always skip, even if not in .gitignore
# (these are near-universal noise in Android projects)
ALWAYS_SKIP_DIRS=(
    ".git"
    ".gradle"
    ".idea"
    "build"
    ".cxx"
    "captures"
    "CODEBASE_CACHE_FOR_AGENTS"    # don't bundle ourselves
)

# Files to always skip by name
ALWAYS_SKIP_FILES=(
    "gradlew"
    "gradlew.bat"
    "local.properties"
)

# Max lines to emit per file before truncating with a notice
# Set to 0 to disable truncation
MAX_LINES_PER_FILE=300

# Strip blank lines from output? (reduces token count ~10-15%)
STRIP_BLANK_LINES=true

# Strip single-line comments? (// ...) — saves tokens but loses some docs
# Set to false if you want KDoc preserved
STRIP_LINE_COMMENTS=false

# ── Helpers ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RESET='\033[0m'

log()  { echo -e "${CYAN}[bundle]${RESET} $*"; }
warn() { echo -e "${YELLOW}[warn]${RESET}  $*"; }
ok()   { echo -e "${GREEN}[ok]${RESET}    $*"; }
err()  { echo -e "${RED}[error]${RESET} $*" >&2; }

# Check if a path is ignored by git (respects all .gitignore files in the tree)
is_git_ignored() {
    local path="$1"
    # --quiet: exit 0 if ignored, 1 if not
    git -C "$PROJECT_ROOT" check-ignore --quiet "$path" 2>/dev/null
}

# Check if a directory name is in the always-skip list
is_always_skip_dir() {
    local dir_name="$1"
    for skip in "${ALWAYS_SKIP_DIRS[@]}"; do
        [[ "$dir_name" == "$skip" ]] && return 0
    done
    return 1
}

# Check if a filename is in the always-skip list
is_always_skip_file() {
    local file_name="$1"
    for skip in "${ALWAYS_SKIP_FILES[@]}"; do
        [[ "$file_name" == "$skip" ]] && return 0
    done
    return 1
}

# Check if a file's extension is in the include list
is_included_extension() {
    local file="$1"
    local ext="${file##*.}"
    for included in "${INCLUDE_EXTENSIONS[@]}"; do
        [[ "$ext" == "$included" ]] && return 0
    done
    return 1
}

# Strip and emit a file's content into the bundle
emit_file() {
    local abs_path="$1"
    local rel_path="$2"
    local out="$3"

    local total_lines
    total_lines=$(wc -l < "$abs_path")

    # ── File header ──
    {
        echo ""
        echo "════════════════════════════════════════════════════════════════"
        echo "=== $rel_path ==="
        echo "════════════════════════════════════════════════════════════════"
    } >> "$out"

    # ── Metadata line (package + class-level declarations) ──
    local pkg=""
    if [[ "$abs_path" == *.kt || "$abs_path" == *.kts ]]; then
        pkg=$(grep -m1 "^package " "$abs_path" 2>/dev/null || true)
        local import_count
        import_count=$(grep -c "^import " "$abs_path" 2>/dev/null | xargs || echo 0)
        local declarations
        declarations=$(grep -E "^(class|interface|object|enum class|sealed class|abstract class|data class|fun |typealias)" "$abs_path" \
            | head -10 | tr '\n' ' ' || true)
        {
            [[ -n "$pkg" ]] && echo "[PKG: ${pkg#package }]"
            echo "[IMPORTS: $import_count omitted]"
            [[ -n "$declarations" ]] && echo "[DECLARES: $declarations]"
        } >> "$out"
    fi

    # ── Content processing ──
    local content
    content=$(cat "$abs_path")

    # Strip import blocks (already counted above for .kt files)
    if [[ "$abs_path" == *.kt || "$abs_path" == *.kts ]]; then
        content=$(echo "$content" | grep -v "^import ")
    fi

    # Strip single-line comments if configured
    if [[ "$STRIP_LINE_COMMENTS" == true ]]; then
        content=$(echo "$content" | sed 's|//.*$||')
    fi

    # Strip blank lines if configured
    if [[ "$STRIP_BLANK_LINES" == true ]]; then
        content=$(echo "$content" | grep -v "^[[:space:]]*$")
    fi

    local processed_lines
    processed_lines=$(echo "$content" | wc -l)

    # Truncate if over the limit
    if [[ "$MAX_LINES_PER_FILE" -gt 0 && "$processed_lines" -gt "$MAX_LINES_PER_FILE" ]]; then
        echo "$content" | head -n "$MAX_LINES_PER_FILE" >> "$out"
        {
            echo ""
            echo "... [TRUNCATED: $((processed_lines - MAX_LINES_PER_FILE)) more lines — use read_file for full content]"
        } >> "$out"
    else
        echo "$content" >> "$out"
    fi
}

# ── Main ─────────────────────────────────────────────────────────────────────
main() {
    # Validate project root
    if [[ ! -d "$PROJECT_ROOT" ]]; then
        err "Project root not found: $PROJECT_ROOT"
        exit 1
    fi

    # Must be a git repo for .gitignore support
    if ! git -C "$PROJECT_ROOT" rev-parse --git-dir &>/dev/null; then
        err "$PROJECT_ROOT is not a git repository. git is required for .gitignore support."
        err "Tip: run 'git init' in your project root first if this is a new project."
        exit 1
    fi

    # Prepare output directory
    local out_dir
    out_dir=$(dirname "$OUTPUT_FILE")
    mkdir -p "$out_dir"

    log "Project root : $PROJECT_ROOT"
    log "Output file  : $OUTPUT_FILE"
    log "Extensions   : ${INCLUDE_EXTENSIONS[*]}"

    # ── Bundle header ────────────────────────────────────────────────────────
    local git_branch git_hash
    git_branch=$(git -C "$PROJECT_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
    git_hash=$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")

    cat > "$OUTPUT_FILE" <<EOF
╔══════════════════════════════════════════════════════════════════╗
║              FILESEARCHER CODEBASE BUNDLE                        ║
╚══════════════════════════════════════════════════════════════════╝
Generated : $(date -u '+%Y-%m-%d %H:%M:%S UTC')
Branch    : $git_branch @ $git_hash
Root      : $PROJECT_ROOT
Extensions: ${INCLUDE_EXTENSIONS[*]}
Settings  : strip_blank_lines=$STRIP_BLANK_LINES strip_comments=$STRIP_LINE_COMMENTS max_lines_per_file=$MAX_LINES_PER_FILE

USAGE FOR FILESEARCHER:
  This bundle contains a stripped snapshot of the codebase.
  Prefer answering from this context before issuing tool calls.
  Files marked [TRUNCATED] should be fetched in full via read_file when needed.
  Import blocks are omitted — counts are shown in [IMPORTS: N omitted] headers.
══════════════════════════════════════════════════════════════════════

EOF

    # ── Walk the project tree ─────────────────────────────────────────────────
    local file_count=0
    local skip_count=0
    local total_lines_written=0

    # Build the -prune expression for find dynamically from ALWAYS_SKIP_DIRS.
    # This prevents find from ever descending into those directories, so we
    # never pay the per-file overhead for the thousands of files inside build/,
    # .gradle/, .idea/, etc.
    #
    # Produces:  \( -name ".git" -o -name ".gradle" -o ... \) -prune -o -type f -print0
    local prune_expr=()
    prune_expr+=("(")
    for skip_dir in "${ALWAYS_SKIP_DIRS[@]}"; do
        if [[ ${#prune_expr[@]} -gt 1 ]]; then
            prune_expr+=("-o")
        fi
        prune_expr+=("-name" "$skip_dir")
    done
    prune_expr+=(")" "-prune" "-o" "-type" "f" "-print0")

    while IFS= read -r -d '' abs_path; do
        local rel_path="${abs_path#$PROJECT_ROOT/}"
        local file_name
        file_name=$(basename "$abs_path")

        # 1. Always-skip files by name
        if is_always_skip_file "$file_name"; then
            ((skip_count++)) || true
            continue
        fi

        # 2. Extension filter
        if ! is_included_extension "$file_name"; then
            continue
        fi

        # 3. .gitignore check (most expensive — do it last)
        if is_git_ignored "$abs_path"; then
            ((skip_count++)) || true
            log "  gitignore → $rel_path"
            continue
        fi

        # ── Emit ──
        log "  bundling  → $rel_path"
        emit_file "$abs_path" "$rel_path" "$OUTPUT_FILE"
        ((file_count++)) || true

    done < <(find "$PROJECT_ROOT" "${prune_expr[@]}" | sort -z)

    # ── Bundle footer ─────────────────────────────────────────────────────────
    total_lines_written=$(wc -l < "$OUTPUT_FILE")

    cat >> "$OUTPUT_FILE" <<EOF


══════════════════════════════════════════════════════════════════════
END OF BUNDLE
Files included : $file_count
Files skipped  : $skip_count (gitignored or excluded by config)
Total lines    : $total_lines_written
══════════════════════════════════════════════════════════════════════
EOF

    ok "Bundle complete!"
    ok "  Files included : $file_count"
    ok "  Files skipped  : $skip_count"
    ok "  Output lines   : $total_lines_written"
    ok "  Output file    : $OUTPUT_FILE"

    # Token estimate (rough: 1 token ≈ 4 chars for code)
    local byte_count
    byte_count=$(wc -c < "$OUTPUT_FILE")
    local token_estimate=$(( byte_count / 4 ))
    log "  Token estimate : ~$token_estimate tokens (rough, code-weighted)"
}

main "$@"