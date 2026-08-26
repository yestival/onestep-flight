param(
    [string]$Destination,
    [string]$OfflineSource
)

$ErrorActionPreference = 'Stop'
$sourceRevision = '5d623a6969a1adee7961cf1c9a8a212c4a784713'
$sourceUri = "https://raw.githubusercontent.com/jpatokal/openflights/$sourceRevision/data/airlines.dat"
$expectedSha256 = '39be1a432e8b04ebc12860c29281c974a9cb52169c82b2456a835d66ab1548a1'
$expectedRecordCount = 6162
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($Destination)) {
    $Destination = Join-Path $repositoryRoot 'src/main/resources/airlines/openflights-airlines.dat'
}

$resolvedDestination = [System.IO.Path]::GetFullPath($Destination)
$temporaryPath = [System.IO.Path]::GetTempFileName()

try {
    if ([string]::IsNullOrWhiteSpace($OfflineSource)) {
        Invoke-WebRequest -UseBasicParsing -Uri $sourceUri -OutFile $temporaryPath
    }
    else {
        $offlinePath = [System.IO.Path]::GetFullPath($OfflineSource)
        [System.IO.File]::WriteAllBytes($temporaryPath, [System.IO.File]::ReadAllBytes($offlinePath))
    }

    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryPath).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "OpenFlights snapshot checksum mismatch: expected $expectedSha256, found $actualSha256"
    }

    $actualRecordCount = [System.IO.File]::ReadAllLines($temporaryPath).Length
    if ($actualRecordCount -ne $expectedRecordCount) {
        throw "OpenFlights snapshot row count mismatch: expected $expectedRecordCount, found $actualRecordCount"
    }

    $destinationDirectory = Split-Path -Parent $resolvedDestination
    [System.IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null
    [System.IO.File]::WriteAllBytes($resolvedDestination, [System.IO.File]::ReadAllBytes($temporaryPath))

    [pscustomobject]@{
        SourceRevision = $sourceRevision
        Destination = $resolvedDestination
        RecordCount = $actualRecordCount
        Sha256 = $actualSha256
    }
}
finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
}
