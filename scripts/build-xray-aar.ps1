param(
    [string]$Output = "app/libs/xray.aar",
    [int]$AndroidApi = 26,
    [string]$XrayCoreRef = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required."
    }
}

function Invoke-Checked {
    param(
        [scriptblock]$Command,
        [string]$ErrorMessage
    )
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$ErrorMessage (exit code: $LASTEXITCODE)"
    }
}

Require-Command "go"

$workspace = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputPath = Join-Path $workspace $Output
$outputDir = Split-Path $outputPath -Parent
$xrayCoreRefValue = $XrayCoreRef
if ([string]::IsNullOrWhiteSpace($xrayCoreRefValue)) {
    $xrayCoreRefValue = $env:XRAY_CORE_REF
}
if ([string]::IsNullOrWhiteSpace($xrayCoreRefValue)) {
    $xrayCoreRefValue = "main"
}

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

Push-Location (Join-Path $workspace "xray-go")
try {
    # Bypass checksum DB for Xray-core (module path lacks the required /vN suffix).
    $env:GONOSUMDB = "github.com/xtls/xray-core"

    Write-Host "Resolving xray-core@$xrayCoreRefValue..."
    Invoke-Checked { go get "github.com/xtls/xray-core@$xrayCoreRefValue" } "go get xray-core failed"

    # Tidy after resolving xray-core so its transitive deps are pruned correctly.
    Invoke-Checked { go mod tidy } "go mod tidy failed"

    # Add x/mobile AFTER tidy so tidy doesn't remove it (nothing in our source imports it directly).
    Write-Host "Resolving golang.org/x/mobile..."
    Invoke-Checked { go get golang.org/x/mobile@latest } "go get golang.org/x/mobile failed"

    # Extract the exact x/mobile version now in go.mod.
    # gomobile and gobind MUST be installed at this exact version — a mismatch causes
    # gobind to fail with "no Go package in golang.org/x/mobile/bind".
    $mobileVersion = (go list -m golang.org/x/mobile) -split '\s+' | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($mobileVersion)) {
        throw "Failed to resolve golang.org/x/mobile version."
    }
    Write-Host "Installing gomobile and gobind at $mobileVersion..."
    Invoke-Checked { go install "golang.org/x/mobile/cmd/gomobile@$mobileVersion" } "go install gomobile failed"
    Invoke-Checked { go install "golang.org/x/mobile/cmd/gobind@$mobileVersion" } "go install gobind failed"
    Require-Command "gomobile"

    Write-Host "Initializing gomobile..."
    Invoke-Checked { gomobile init } "gomobile init failed"

    Write-Host "Running gomobile bind..."
    Invoke-Checked {
        gomobile bind `
            -target=android `
            "-androidapi=$($AndroidApi)" `
            -o $outputPath `
            .
    } "gomobile bind failed"

    if (-not (Test-Path $outputPath)) {
        throw "gomobile bind completed without producing $outputPath"
    }
}
finally {
    Pop-Location
    Remove-Item Env:\GONOSUMDB -ErrorAction SilentlyContinue
}

Write-Host "AAR generated at $outputPath"
