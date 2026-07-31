param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$UsersFile = (Join-Path $PSScriptRoot 'qps-users.csv'),
    [string]$ItemId = '940000000000000000',
    [ValidateRange(0, 10000)]
    [int]$Skip = 0,
    [ValidateRange(1, 10000)]
    [int]$Count = 100,
    [string]$OutputFile = (Join-Path $PSScriptRoot 'token-paths.csv')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

$users = @(Import-Csv -LiteralPath $UsersFile | Select-Object -Skip $Skip -First $Count)
if ($users.Count -ne $Count) {
    throw "UsersFile does not contain $Count users after Skip=$Skip; found $($users.Count)"
}

$outputDirectory = Split-Path -Parent $OutputFile
if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}

$rows = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $users.Count; $index++) {
    $user = $users[$index]
    $loginBody = @{
        username = $user.username
        password = $user.password
    } | ConvertTo-Json -Compress

    try {
        $login = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/api/auth/login" `
            -ContentType 'application/json' `
            -Body $loginBody `
            -TimeoutSec 15
    }
    catch {
        throw "Login failed for $($user.username): $($_.Exception.Message)"
    }

    $token = [string]$login.data.accessToken
    if ([int]$login.code -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
        throw "Login failed for $($user.username): $($login | ConvertTo-Json -Compress)"
    }

    try {
        $pathResult = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/api/seckill/$ItemId/path" `
            -Headers @{ Authorization = "Bearer $token" } `
            -TimeoutSec 15
    }
    catch {
        throw "Path request failed for $($user.username): $($_.Exception.Message)"
    }

    $path = [string]$pathResult.data
    if ([int]$pathResult.code -ne 0 -or [string]::IsNullOrWhiteSpace($path)) {
        throw "Path request failed for $($user.username): $($pathResult | ConvertTo-Json -Compress)"
    }

    $rows.Add([PSCustomObject]@{
            token = $token
            path  = $path
        })

    if (($index + 1) % 50 -eq 0 -or $index -eq $users.Count - 1) {
        Write-Host ("[{0}/{1}] prepared token and path for {2}" -f ($index + 1), $Count, $user.username)
    }
}

$rows | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8
$preheatFile = Join-Path (Split-Path -Parent $OutputFile) 'preheat-token.csv'
$rows | Select-Object -First 1 | Export-Csv -LiteralPath $preheatFile -NoTypeInformation -Encoding UTF8
Write-Host "Token/path CSV generated: $OutputFile"
Write-Host "Preheat token CSV generated: $preheatFile"
