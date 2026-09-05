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

# outputFileName now bakes in a build timestamp + git commit (see
# app/build.gradle's androidComponents.onVariants block), so it can no
# longer be hardcoded here - grab the most recently built release APK from
# the output directory instead.
$releaseApkDir = "D:\AndroidBuilds\$(Split-Path $root -Leaf)\app\outputs\apk\release"
$builtApk = Get-ChildItem -Path $releaseApkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $builtApk) { Write-Error "No release APK found in $releaseApkDir - build the release variant first."; exit 1 }
$builtApkPath = $builtApk.FullName
$apkName = "FuZzVolumeHU_$tag.apk"
# gh uploads assets under the basename of the path given to it, so the built
# APK is copied under the name we actually want released first.
$apkPath = Join-Path $releaseApkDir $apkName
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
# Get-Content -Encoding UTF8 can leave a leading BOM character on the first
# line it reads - strip it so it doesn't show up as a stray glyph in the
# published release body.
$releaseNotes = $releaseNotes.TrimStart([char]0xFEFF)

Write-Output "Publishing $tag (versionName $versionName) to $owner/$repo ..."

# gh writes "release not found" to stderr with a non-zero exit when the tag
# doesn't exist yet (the expected/normal case here) - PowerShell 5.1 turns
# that into a terminating NativeCommandError under $ErrorActionPreference
# 'Stop' even with 2>$null, so it's relaxed to 'Continue' for just this probe.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
gh release view $tag --repo "$owner/$repo" *> $null
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEAP
if ($releaseExists) {
    Write-Output "Release $tag already exists - not creating a duplicate."
    exit 0
}

$notesFile = New-TemporaryFile
Set-Content -Path $notesFile -Value $releaseNotes -Encoding UTF8

gh release create $tag $apkPath --repo "$owner/$repo" --title $tag --notes-file $notesFile
Remove-Item $notesFile -Force

Write-Output "Published $apkName as $tag - https://github.com/$owner/$repo/releases/tag/$tag"
