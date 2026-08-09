# Builds one planner request body exactly as SarvamPlanner does, writes it as
# UTF-8 JSON, and POSTs it with curl.exe. PowerShell 5.1's Invoke-RestMethod is
# deliberately not used here — it was hanging and burning CPU on this payload,
# which is a harness problem, not a Sarvam one.
param(
  [Parameter(Mandatory=$true)][string]$Said,
  [Parameter(Mandatory=$true)][string]$Lang
)
$ErrorActionPreference = "Stop"
$root = "C:\Projects\Sarvam\ScreenSaathi"
$sp   = "C:\Users\nitis\AppData\Local\Temp\claude\C--Projects-Sarvam\dba325a3-0f15-439d-9311-f4e11c48cf47\scratchpad"

$line = Get-Content "$root\local.properties" | Where-Object { $_ -match '^\s*sarvam\.api\.key\s*=' } | Select-Object -First 1
$Key = ($line -split '=',2)[1].Trim()

$prompt = [IO.File]::ReadAllText("$root\src\ScreenSaathi\app\src\main\assets\prompts\planner_v1.md", [Text.UTF8Encoding]::new($false))

$screen = @"
Screen: com.screensaathi
Elements:
[0] TextView "Pay Electricity Bill"
[1] EditText id=amount_field E
[2] EditText id=account_field E
[3] Button id=submit_button "Pay Bill" C
"@

$user = "User said: `"$Said`"`nDetected spoken language: $Lang - reply in this language, in its own script.`n`nTask: pay_bill - Pay Electricity Bill`nSteps:`n- amount (resource_id=amount_field)  <- CURRENT`n- account (resource_id=account_field)`n- submit (resource_id=submit_button)`n`n$screen"

function J([string]$s) {
  $sb = New-Object Text.StringBuilder
  foreach ($ch in $s.ToCharArray()) {
    $c = [int][char]$ch
    switch ($ch) {
      '"'  { [void]$sb.Append('\"'); continue }
      '\'  { [void]$sb.Append('\\'); continue }
      "`n" { [void]$sb.Append('\n'); continue }
      "`r" { [void]$sb.Append('\r'); continue }
      "`t" { [void]$sb.Append('\t'); continue }
      default {
        if ($c -lt 32 -or $c -gt 126) { [void]$sb.AppendFormat('\u{0:x4}', $c) }
        else { [void]$sb.Append($ch) }
      }
    }
  }
  $sb.ToString()
}

$body = '{"model":"sarvam-105b","messages":[{"role":"system","content":"' + (J $prompt) + '"},{"role":"user","content":"' + (J $user) + '"}],' +
        '"tools":[{"type":"function","function":{"name":"set_plan","description":"Set the next guided step and the element to point at.","parameters":' +
        '{"type":"object","properties":{"intent":{"type":"string"},"step":{"type":"string","enum":["amount","account","submit"]},' +
        '"target":{"type":"object","properties":{"resource_id":{"type":"string"},"index":{"type":"integer"}},"required":["resource_id","index"]},' +
        '"instruction":{"type":"string"},"language":{"type":"string","enum":["en-IN","hi-IN","bn-IN","gu-IN","kn-IN","ml-IN","mr-IN","pa-IN","ta-IN","te-IN"]},' +
        '"confidence":{"type":"number"},"reason":{"type":"string"}},' +
        '"required":["intent","step","target","instruction","language","confidence","reason"]}}}],' +
        '"tool_choice":"required","parallel_tool_calls":false,"reasoning_effort":null,"temperature":0.1,"max_tokens":300}'

$bodyFile = "$sp\body.json"
[IO.File]::WriteAllText($bodyFile, $body, [Text.UTF8Encoding]::new($false))

$curl = "$env:SystemRoot\System32\curl.exe"
$sw = [Diagnostics.Stopwatch]::StartNew()
$resp = & $curl -s --max-time 30 -X POST "https://api.sarvam.ai/v1/chat/completions" `
  -H "api-subscription-key: $Key" -H "Content-Type: application/json" `
  --data-binary "@$bodyFile" 2>&1 | Out-String
$sw.Stop()

Write-Output "ms=$($sw.ElapsedMilliseconds)"
Write-Output $resp
