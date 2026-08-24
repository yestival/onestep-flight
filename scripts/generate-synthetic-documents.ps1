param(
    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\test-fixtures\documents')
)

$ErrorActionPreference = 'Stop'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
$ascii = [System.Text.Encoding]::ASCII

function ConvertTo-PdfLiteral {
    param([string] $Value)
    return $Value.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)')
}

function New-SyntheticPdf {
    param(
        [string] $Filename,
        [string] $Heading,
        [string[]] $Lines
    )

    $commands = [System.Collections.Generic.List[string]]::new()
    $commands.Add('BT')
    $commands.Add('/F1 15 Tf')
    $commands.Add('0.75 0.08 0.08 rg')
    $commands.Add('52 744 Td')
    # PDF octal 227 is the em dash glyph in WinAnsiEncoding. Keeping this
    # source file ASCII also makes it reliable in Windows PowerShell 5.1.
    $commands.Add('(SYNTHETIC TEST DOCUMENT \227 NOT VALID FOR TRAVEL) Tj')
    $commands.Add('/F1 27 Tf')
    $commands.Add('0.04 0.16 0.35 rg')
    $commands.Add('0 -55 Td')
    $commands.Add('(' + (ConvertTo-PdfLiteral $Heading) + ') Tj')
    $commands.Add('/F1 16 Tf')
    $commands.Add('0.10 0.18 0.28 rg')
    foreach ($line in $Lines) {
        $commands.Add('0 -36 Td')
        $commands.Add('(' + (ConvertTo-PdfLiteral $line) + ') Tj')
    }
    $commands.Add('ET')
    $stream = ($commands -join "`n") + "`n"
    $objects = @(
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>',
        ("<< /Length $($ascii.GetByteCount($stream)) >>`nstream`n$stream" + 'endstream')
    )

    $builder = [System.Text.StringBuilder]::new()
    [void] $builder.Append("%PDF-1.4`n%Synthetic OneStep Flight fixture`n")
    $offsets = [System.Collections.Generic.List[int]]::new()
    for ($index = 0; $index -lt $objects.Count; $index++) {
        $offsets.Add($ascii.GetByteCount($builder.ToString()))
        [void] $builder.Append(($index + 1).ToString() + " 0 obj`n" + $objects[$index] + "`nendobj`n")
    }
    $xrefOffset = $ascii.GetByteCount($builder.ToString())
    $xrefEntries = $objects.Count + 1
    [void] $builder.Append("xref`n0 $xrefEntries`n0000000000 65535 f `n")
    foreach ($offset in $offsets) {
        [void] $builder.Append($offset.ToString('0000000000') + " 00000 n `n")
    }
    [void] $builder.Append("trailer`n<< /Size $xrefEntries /Root 1 0 R >>`nstartxref`n$xrefOffset`n%%EOF`n")
    [System.IO.File]::WriteAllBytes((Join-Path $resolvedOutput $Filename), $ascii.GetBytes($builder.ToString()))
}

function New-SyntheticPng {
    param(
        [string] $Filename,
        [string] $Heading,
        [string[]] $Lines
    )

    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(1200, 800)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $background = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(246, 250, 255))
    $warningBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(184, 35, 48))
    $headingBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(11, 45, 91))
    $bodyBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(31, 50, 72))
    $borderPen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(76, 122, 170), 4)
    $warningFont = [System.Drawing.Font]::new('Arial', 24, [System.Drawing.FontStyle]::Bold)
    $headingFont = [System.Drawing.Font]::new('Arial', 42, [System.Drawing.FontStyle]::Bold)
    $bodyFont = [System.Drawing.Font]::new('Arial', 25, [System.Drawing.FontStyle]::Regular)
    try {
        $graphics.FillRectangle($background, 0, 0, 1200, 800)
        $graphics.DrawRectangle($borderPen, 28, 28, 1144, 744)
        $fixtureWarning = 'SYNTHETIC TEST DOCUMENT ' + [char]0x2014 + ' NOT VALID FOR TRAVEL'
        $graphics.DrawString($fixtureWarning, $warningFont, $warningBrush, 58, 58)
        $graphics.DrawString($Heading, $headingFont, $headingBrush, 58, 135)
        $y = 240
        foreach ($line in $Lines) {
            $graphics.DrawString($line, $bodyFont, $bodyBrush, 62, $y)
            $y += 62
        }
        $bitmap.Save((Join-Path $resolvedOutput $Filename), [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $bodyFont.Dispose()
        $headingFont.Dispose()
        $warningFont.Dispose()
        $borderPen.Dispose()
        $bodyBrush.Dispose()
        $headingBrush.Dispose()
        $warningBrush.Dispose()
        $background.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

New-SyntheticPdf -Filename 'booking-confirmation.pdf' -Heading 'BOOKING CONFIRMATION' -Lines @(
    'Flight: BA 117',
    'Departure date: 2026-08-24',
    'Route: LHR to JFK',
    'Scheduled departure: 08:20 local time',
    'Check-in status: NOT CONFIRMED',
    'Fixture expectation: start with CHECK_IN'
)

New-SyntheticPdf -Filename 'e-ticket-itinerary.pdf' -Heading 'E-TICKET ITINERARY' -Lines @(
    'Flight: BA 117',
    'Departure date: 2026-08-24',
    'Route: LHR to JFK',
    'Scheduled departure: 08:20 local time',
    'This itinerary is not a boarding pass',
    'Fixture expectation: start with CHECK_IN'
)

New-SyntheticPdf -Filename 'boarding-pass.pdf' -Heading 'BOARDING PASS' -Lines @(
    'Flight: BA 117',
    'Departure date: 2026-08-24',
    'Route: LHR to JFK',
    'Boarding time: 07:35 local time',
    'Departure terminal: 5    Gate: B42',
    'Check-in completed - fixture may skip CHECK_IN'
)

New-SyntheticPng -Filename 'booking-page-screenshot.png' -Heading 'ONLINE BOOKING CONFIRMATION' -Lines @(
    'Flight: BA 117',
    'Departure date: 2026-08-24',
    'Route: LHR to JFK',
    'Scheduled departure: 08:20 local time',
    'Check-in has not been completed',
    'Fixture expectation: start with CHECK_IN'
)

New-SyntheticPng -Filename 'non-flight-document.png' -Heading 'ACCESSIBILITY PRACTICE NOTE' -Lines @(
    'Practice asking airport staff for directions.',
    'Keep travel documents in an easy-to-reach place.',
    'This image contains no flight identity or schedule.',
    'Fixture expectation: UNKNOWN and manual entry required.'
)
