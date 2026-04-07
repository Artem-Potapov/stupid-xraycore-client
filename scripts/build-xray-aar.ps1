param(
    [string]$Output = "app/libs/xray.aar",
    [int]$AndroidApi = 26
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command gomobile -ErrorAction SilentlyContinue)) {
    throw "gomobile is required. Install it with: go install golang.org/x/mobile/cmd/gomobile@latest"
}

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "go is required."
}

go env | Out-Null
gomobile version | Out-Null

$workspace = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputPath = Join-Path $workspace $Output
$outputDir = Split-Path $outputPath -Parent

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

Push-Location (Join-Path $workspace "xray-go")
try {
    go mod tidy
    gomobile bind `
        -target=android `
        -androidapi=$AndroidApi `
        -o $outputPath `
        .
}
finally {
    Pop-Location
}

Write-Host "AAR generated at $outputPath"
