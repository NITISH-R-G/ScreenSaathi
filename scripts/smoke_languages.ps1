# Verifies the multilingual round trip ScreenSaathi depends on, per language:
#   Bulbul TTS (does this language + speaker work?)
#     -> Saaras STT (does it come back, and is language_code detected correctly?)
#
# This is the evidence behind the language list in sarvam/Language.kt. Run it
# again if Sarvam changes models or speakers.
#
#     powershell -File scripts\smoke_languages.ps1
param([string]$Key, [string]$Speaker = "anand")

$ErrorActionPreference = "Continue"

if ([string]::IsNullOrWhiteSpace($Key)) {
  $propsPath = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
  $line = Get-Content $propsPath | Where-Object { $_ -match '^\s*sarvam\.api\.key\s*=' } | Select-Object -First 1
  if ($line) { $Key = ($line -split '=', 2)[1].Trim() }
  if ([string]::IsNullOrWhiteSpace($Key)) { Write-Host "No key." -ForegroundColor Red; exit 1 }
}
$hdr = @{ "api-subscription-key" = $Key }
$curl = "$env:SystemRoot\System32\curl.exe"

# One natural sentence per language, of the kind the app actually speaks.
$cases = @(
  @{ code = "en-IN"; text = "Enter the bill amount in this box." },
  @{ code = "hi-IN"; text = "इस बॉक्स में बिल की रकम भरिए।" },
  @{ code = "ta-IN"; text = "இந்தப் பெட்டியில் தொகையை உள்ளிடவும்." },
  @{ code = "te-IN"; text = "ఈ పెట్టెలో మొత్తాన్ని నమోదు చేయండి." },
  @{ code = "bn-IN"; text = "এই বাক্সে বিলের পরিমাণ লিখুন।" },
  @{ code = "kn-IN"; text = "ಈ ಪೆಟ್ಟಿಗೆಯಲ್ಲಿ ಮೊತ್ತವನ್ನು ನಮೂದಿಸಿ." },
  @{ code = "ml-IN"; text = "ഈ ബോക്സിൽ തുക നൽകുക." },
  @{ code = "mr-IN"; text = "या बॉक्समध्ये बिलाची रक्कम भरा." },
  @{ code = "gu-IN"; text = "આ બોક્સમાં બિલની રકમ ભરો." },
  @{ code = "pa-IN"; text = "ਇਸ ਬਾਕਸ ਵਿੱਚ ਬਿੱਲ ਦੀ ਰਕਮ ਭਰੋ।" }
)

Write-Host "`nspeaker = $Speaker`n" -ForegroundColor DarkGray
$ok = @(); $bad = @()

foreach ($c in $cases) {
  $body = @{
    text = $c.text; target_language_code = $c.code
    speaker = $Speaker; model = "bulbul:v3"
  } | ConvertTo-Json
  $utf8 = [Text.Encoding]::UTF8.GetBytes($body)
  try {
    $t = Measure-Command {
      $script:r = Invoke-RestMethod -Uri "https://api.sarvam.ai/text-to-speech" -Method Post `
        -Headers $hdr -ContentType "application/json; charset=utf-8" -Body $utf8
    }
    if (-not $r.audios -or $r.audios.Count -eq 0) {
      Write-Host ("{0,-7} TTS: no audio" -f $c.code) -ForegroundColor Yellow; $bad += $c.code; continue
    }
    $bytes = [Convert]::FromBase64String($r.audios[0])
    $wav = "$PSScriptRoot\lang_$($c.code).wav"
    [IO.File]::WriteAllBytes($wav, $bytes)

    $raw = & $curl -s -X POST "https://api.sarvam.ai/speech-to-text" `
      -H "api-subscription-key: $Key" `
      -F "model=saaras:v3" -F "mode=transcribe" -F "file=@$wav;type=audio/wav" 2>&1 | Out-String
    $p = $null; try { $p = $raw | ConvertFrom-Json } catch {}
    Remove-Item $wav -ErrorAction SilentlyContinue

    $detected = if ($p) { $p.language_code } else { "?" }
    $match = if ($detected -eq $c.code) { "OK  " } else { "DIFF" }
    $colour = if ($detected -eq $c.code) { "Green" } else { "Yellow" }
    Write-Host ("{0,-7} TTS {1,5}ms  detected={2,-7} {3}  '{4}'" -f `
      $c.code, [int]$t.TotalMilliseconds, $detected, $match, $p.transcript) -ForegroundColor $colour
    $ok += $c.code
  } catch {
    Write-Host ("{0,-7} FAIL: {1}" -f $c.code, $_.Exception.Message) -ForegroundColor Red
    if ($_.ErrorDetails) { Write-Host ("        " + $_.ErrorDetails.Message) -ForegroundColor DarkRed }
    $bad += $c.code
  }
}

Write-Host "`nusable: $($ok -join ', ')" -ForegroundColor Cyan
if ($bad) { Write-Host "failed: $($bad -join ', ')" -ForegroundColor Red }
Write-Host ""
