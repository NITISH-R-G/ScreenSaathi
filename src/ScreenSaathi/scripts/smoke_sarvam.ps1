# Smoke-tests the three Sarvam surfaces ScreenSaathi depends on, before trusting
# them inside the app.
#
# Run from the project root (C:\Projects\Sarvam\ScreenSaathi):
#     powershell -File scripts\smoke_sarvam.ps1
#
# By default it reads sarvam.api.key from local.properties, so the key never
# lands in your shell history. Override with -Key only if you need to.
param([string]$Key)

$ErrorActionPreference = "Continue"

if ([string]::IsNullOrWhiteSpace($Key)) {
  $propsPath = Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"
  if (-not (Test-Path $propsPath)) {
    Write-Host "No -Key given and local.properties not found at $propsPath" -ForegroundColor Red
    exit 1
  }
  $line = Get-Content $propsPath | Where-Object { $_ -match '^\s*sarvam\.api\.key\s*=' } | Select-Object -First 1
  if ($line) { $Key = ($line -split '=', 2)[1].Trim() }
  if ([string]::IsNullOrWhiteSpace($Key)) {
    Write-Host "sarvam.api.key is empty in local.properties." -ForegroundColor Red
    exit 1
  }
  Write-Host "Using key from local.properties (...$($Key.Substring([Math]::Max(0,$Key.Length-4))))" -ForegroundColor DarkGray
}
$hdr = @{ "api-subscription-key" = $Key }

Write-Host "`n=== 1. Sarvam-105B planner (chat + forced tool call) ===" -ForegroundColor Cyan
$chatBody = @{
  model = "sarvam-105b"
  messages = @(
    @{ role = "system"; content = "Call set_plan once. Never reply with prose." },
    @{ role = "user"; content = "User said: 'help me pay this bill'. Steps: amount, account, submit." }
  )
  tools = @(@{
    type = "function"
    function = @{
      name = "set_plan"
      description = "Set the next guided step."
      parameters = @{
        type = "object"
        properties = @{
          step = @{ type = "string"; enum = @("amount","account","submit") }
          instruction = @{ type = "string" }
          confidence = @{ type = "number" }
        }
        required = @("step","instruction","confidence")
      }
    }
  })
  tool_choice = "required"
  reasoning_effort = $null
  temperature = 0.1
  max_tokens = 200
} | ConvertTo-Json -Depth 10
try {
  $t = Measure-Command { $script:r = Invoke-RestMethod -Uri "https://api.sarvam.ai/v1/chat/completions" -Method Post -Headers $hdr -ContentType "application/json" -Body $chatBody }
  $tc = $r.choices[0].message.tool_calls
  if ($tc) { Write-Host "OK ($([int]$t.TotalMilliseconds)ms) tool_call args: $($tc[0].function.arguments)" -ForegroundColor Green }
  else { Write-Host "NO TOOL CALL. content: $($r.choices[0].message.content)" -ForegroundColor Yellow }
} catch { Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; if ($_.ErrorDetails) { Write-Host $_.ErrorDetails.Message } }

Write-Host "`n=== 2. Bulbul v3 TTS (speaker=anand) ===" -ForegroundColor Cyan
$ttsBody = @{
  text = "Namaste, bill amount yahan bhariye."
  target_language_code = "hi-IN"
  speaker = "anand"
  model = "bulbul:v3"
} | ConvertTo-Json
try {
  $t = Measure-Command { $script:r2 = Invoke-RestMethod -Uri "https://api.sarvam.ai/text-to-speech" -Method Post -Headers $hdr -ContentType "application/json" -Body $ttsBody }
  if ($r2.audios -and $r2.audios.Count -gt 0) {
    $bytes = [Convert]::FromBase64String($r2.audios[0])
    [IO.File]::WriteAllBytes("$PSScriptRoot\tts_test.wav", $bytes)
    Write-Host "OK ($([int]$t.TotalMilliseconds)ms) wrote tts_test.wav ($($bytes.Length) bytes) - play it to verify the voice" -ForegroundColor Green
  } else { Write-Host "NO AUDIO in response" -ForegroundColor Yellow }
} catch { Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; if ($_.ErrorDetails) { Write-Host $_.ErrorDetails.Message } }

Write-Host "`n=== 3. Saaras v3 STT (round-trips the TTS clip) ===" -ForegroundColor Cyan
$wav = "$PSScriptRoot\tts_test.wav"
if (Test-Path $wav) {
  # Invoke-RestMethod -Form needs PowerShell 6+; this box runs 5.1, so shell out
  # to the curl.exe that ships with Windows for the multipart upload.
  $curl = "$env:SystemRoot\System32\curl.exe"
  if (-not (Test-Path $curl)) { $curl = "curl.exe" }
  try {
    $t = Measure-Command {
      $script:raw = & $curl -s -X POST "https://api.sarvam.ai/speech-to-text" `
        -H "api-subscription-key: $Key" `
        -F "model=saaras:v3" -F "mode=transcribe" -F "file=@$wav;type=audio/wav" 2>&1 | Out-String
    }
    $parsed = $null
    try { $parsed = $raw | ConvertFrom-Json } catch {}
    if ($parsed -and $parsed.transcript) {
      Write-Host "OK ($([int]$t.TotalMilliseconds)ms) transcript: '$($parsed.transcript)' lang: $($parsed.language_code)" -ForegroundColor Green
    } else {
      Write-Host "UNEXPECTED RESPONSE ($([int]$t.TotalMilliseconds)ms): $raw" -ForegroundColor Yellow
    }
  } catch { Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red }
} else { Write-Host "SKIP: no tts_test.wav (step 2 must succeed first)" -ForegroundColor Yellow }

Write-Host "`nDone.`n"
