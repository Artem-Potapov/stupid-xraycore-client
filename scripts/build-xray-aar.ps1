param(
    [string]$Output = "app/libs/xray.aar",
    [int]$AndroidApi = 26
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "go is required."
}

$workspace = Resolve-Path (Join-Path $PSScriptRoot "..")
$outputPath = Join-Path $workspace $Output
$outputDir = Split-Path $outputPath -Parent

if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

Push-Location (Join-Path $workspace "xray-go")
try {
    # Bypass checksum DB for Xray-core (module path lacks the required /vN suffix).
    $env:GONOSUMDB = "github.com/xtls/xray-core"

    Write-Host "Resolving xray-core..."
    go get github.com/xtls/xray-core@main

    # Tidy after resolving xray-core so its transitive deps are pruned correctly.
    go mod tidy

    # Add x/mobile AFTER tidy so tidy doesn't remove it (nothing in our source imports it directly).
    Write-Host "Resolving golang.org/x/mobile..."
    go get golang.org/x/mobile@latest

    # Extract the exact x/mobile version now in go.mod.
    # gomobile and gobind MUST be installed at this exact version — a mismatch causes
    # gobind to fail with "no Go package in golang.org/x/mobile/bind".
    $mobileVersion = (go list -m golang.org/x/mobile) -split '\s+' | Select-Object -Last 1
    Write-Host "Installing gomobile and gobind at $mobileVersion..."
    go install "golang.org/x/mobile/cmd/gomobile@$mobileVersion"
    go install "golang.org/x/mobile/cmd/gobind@$mobileVersion"

    Write-Host "Running gomobile bind..."
    gomobile bind `
        -target=android `
        -androidapi=$AndroidApi `
        -o $outputPath `
        .
}
finally {
    Pop-Location
    Remove-Item Env:\GONOSUMDB -ErrorAction SilentlyContinue
}

Write-Host "AAR generated at $outputPath"
