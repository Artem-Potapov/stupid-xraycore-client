<#
.SYNOPSIS
    Gradle build queue — serializes builds so only one runs at a time.

.DESCRIPTION
    Lives in scripts/ next to the other repo helpers. Use it instead of
    gradlew.bat when you may have more than one build in flight.

    If a build is already in progress, your command is queued and runs
    automatically when it's your turn. FIFO is approximate (OS-level
    file-lock scheduling), which is fine for the typical 2-3 terminal case.

    All Gradle argument forms pass through unchanged:
      .\scripts\gradlew_queue.ps1 :app:assembleDebug --stacktrace
      .\scripts\gradlew_queue.ps1 clean build -Penv=prod
      .\scripts\gradlew_queue.ps1 :lib:test --tests "com.example.MyTest"

.NOTES
    Lock file: .gradle\queue.lock   (inside the project, already gitignored)
    Info file: .gradle\queue.active  (tells queued instances what's running)
    Both are cleaned up automatically, even on Ctrl+C.
#>

# ── Resolve paths ────────────────────────────────────────────────────────
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradlew  = Join-Path $projectRoot 'gradlew.bat'
$lockDir  = Join-Path $projectRoot '.gradle'
$lockFile = Join-Path $lockDir 'queue.lock'
$infoFile = Join-Path $lockDir 'queue.active'

if (!(Test-Path $gradlew)) {
    Write-Host "  Error: gradlew.bat not found in $projectRoot" -ForegroundColor Red
    Write-Host "  Expected the Gradle wrapper at the repo root (parent of scripts/)." -ForegroundColor DarkGray
    exit 1
}
if (!(Test-Path $lockDir)) {
    New-Item -ItemType Directory $lockDir -Force | Out-Null
}

# ── Pretty-print the command ─────────────────────────────────────────────
$cmdParts = foreach ($a in $args) {
    if ($a -match '\s') { "`"$a`"" } else { "$a" }
}
$cmdDisplay = if ($cmdParts) { $cmdParts -join ' ' } else { '(no tasks)' }

# ── Acquire exclusive lock (queue gate) ──────────────────────────────────
$stream  = $null
$queued  = $false
$sw      = $null
$spinner = @('|', '/', '-', '\')
$tick    = 0

while ($null -eq $stream) {
    try {
        # FileShare.None = exclusive — only one process can hold this at a time.
        # If another holds it, the constructor throws IOException and we loop.
        $stream = [System.IO.FileStream]::new(
            $lockFile,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    }
    catch [System.IO.IOException] {
        if (!$queued) {
            # First failed attempt — show what's happening
            $active = $null
            try {
                if (Test-Path $infoFile) {
                    $active = (Get-Content $infoFile -Raw -ErrorAction SilentlyContinue).Trim()
                }
            } catch {}

            Write-Host ''
            Write-Host '  Gradle is busy! Your command has been queued, please wait...' -ForegroundColor Yellow
            if ($active) {
                Write-Host "  Running : $active" -ForegroundColor DarkGray
            }
            Write-Host "  Queued  : gradlew $cmdDisplay" -ForegroundColor DarkYellow
            Write-Host ''

            $queued = $true
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
        }

        # Animated spinner with elapsed time
        $ch   = $spinner[$tick % $spinner.Count]
        $secs = [math]::Floor($sw.Elapsed.TotalSeconds)
        Write-Host "`r  $ch Waiting... (${secs}s)  " -NoNewline -ForegroundColor DarkGray
        $tick++
        Start-Sleep -Milliseconds 250
    }
}

# ── Lock acquired ────────────────────────────────────────────────────────
if ($queued) {
    $sw.Stop()
    $waited = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    Write-Host "`r  * Queue cleared after ${waited}s - running now!      " -ForegroundColor Green
    Write-Host ''
}

# Write active-build info so queued instances can see what's running
try { "gradlew $cmdDisplay" | Set-Content $infoFile -Force } catch {}

# ── Run Gradle ───────────────────────────────────────────────────────────
$exitCode = 1
try {
    & $gradlew @args
    $exitCode = $LASTEXITCODE
}
finally {
    # Always release — runs even on Ctrl+C
    if ($stream) {
        $stream.Close()
        $stream.Dispose()
    }
    Remove-Item $infoFile -Force -ErrorAction SilentlyContinue
}

exit $exitCode
