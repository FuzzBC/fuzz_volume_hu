# Publishes the just-built release APK as a GitHub release on
# FuzzBC/fuzz_volume_hu, using the gh CLI (already authenticated as FuzzBC
# on this machine) instead of a stored token - simpler than the other FuZz
# apps' token-file approach since gh handles auth itself.
# Tag is V<versionName> (e.g. V1.001) - UpdateChecker.java pulls the numeric
# versionCode back out of it (the part after the last '.') to compare.

$ErrorActionPreference = 'Stop'

$root  = $PSScriptRoot
$owner = 'FuzzBC'
$repo  = 'fuzz_volume_hu'

$versionPropsPath = Join-Path $root 'version.properties'
$versionPropsText = Get-Content $versionPropsPath -Raw
if ($versionPropsText -notmatch 'versionCode\s*=\s*(\d+)') { Write-Error "versionCode not found in $versionPropsPath"; exit 1 }
$versionCode = [int]$matches[1]
$versionMajor = if ($versionPropsText -match 'versionMajor\s*=\s*(\d+)') { [int]$matches[1] } else { 1 }
$versionName = "$versionMajor." + $versionCode.ToString('000')
$tag = "V$versionName"

$builtApkPath = "D:\AndroidBuilds\$(Split-Path $root -Leaf)\app\outputs\apk\release\app-release.apk"
if (-not (Test-Path $builtApkPath)) { Write-Error "APK not found at $builtApkPath - build the release variant first."; exit 1 }
$apkName = "FuZzVolumeHU_$tag.apk"
# gh uploads assets under the basename of the path given to it, so the built
# app-release.apk is copied under the name we actually want released first.
$apkPath = Join-Path (Split-Path $builtApkPath) $apkName
Copy-Item -Path $builtApkPath -Destination $apkPath -Force

$releaseNotes = "No changelog entry found for $versionName."
$changelogPath = Join-Path $root 'CHANGELOG.md'
if (Test-Path $changelogPath) {
    $lines = Get-Content $changelogPath -Encoding UTF8
    $heading = "## $versionName"
    $startIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq $heading) { $startIdx = $i + 1; break }
    }
    if ($startIdx -ge 0) {
        $endIdx = $lines.Count
        for ($i = $startIdx; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^##\s') { $endIdx = $i; break }
        }
        $entryLines = $lines[$startIdx..($endIdx - 1)] | Where-Object { $_.Trim() -ne '' }
        if ($entryLines.Count -gt 0) { $releaseNotes = ($entryLines -join "`n") }
    }
}

Write-Output "Publishing $tag (versionName $versionName) to $owner/$repo ..."

$existing = gh release view $tag --repo "$owner/$repo" 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Output "Release $tag already exists - not creating a duplicate."
    exit 0
}

$notesFile = New-TemporaryFile
Set-Content -Path $notesFile -Value $releaseNotes -Encoding UTF8

gh release create $tag $apkPath --repo "$owner/$repo" --title $tag --notes-file $notesFile
Remove-Item $notesFile -Force

Write-Output "Published $apkName as $tag - https://github.com/$owner/$repo/releases/tag/$tag"
