param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 10000)]
    [int]$UserCount = 100,
    [string]$Password = 'LoadTest@123456',
    [string]$OutputFile = (Join-Path $PSScriptRoot 'users.csv'),
    [string]$UsernamePrefix = ('loadtest_' + (Get-Date -Format 'yyyyMMddHHmmss'))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$BaseUrl = $BaseUrl.TrimEnd('/')
$outputDirectory = Split-Path -Parent $OutputFile
if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}

$rows = [System.Collections.Generic.List[object]]::new()
$phoneSeed = [int64](Get-Date -Format 'HHmmssff')

for ($index = 1; $index -le $UserCount; $index++) {
    $username = '{0}_{1:D4}' -f $UsernamePrefix, $index
    $phone = '139' + (($phoneSeed + $index) % 100000000).ToString('D8')

    $registerBody = @{
        username = $username
        password = $Password
        phone    = $phone
    } | ConvertTo-Json -Compress

    try {
        $register = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/api/auth/register" `
            -ContentType 'application/json' `
            -Body $registerBody
    }
    catch {
        throw "Register user $username failed: $($_.Exception.Message)"
    }

    if ([int]$register.code -ne 0 -or $null -eq $register.data.accessToken) {
        throw "Register user $username failed: $($register | ConvertTo-Json -Compress)"
    }

    $addressBody = @{
        receiver  = "LoadTestUser$index"
        phone     = $phone
        province  = 'Guangdong'
        city      = 'Shenzhen'
        district  = 'Nanshan'
        detail    = "LoadTestAddress$index"
        isDefault = 1
    } | ConvertTo-Json -Compress

    try {
        $address = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/api/user/address" `
            -Headers @{ Authorization = "Bearer $($register.data.accessToken)" } `
            -ContentType 'application/json' `
            -Body $addressBody
    }
    catch {
        throw "Create address for $username failed: $($_.Exception.Message)"
    }

    if ([int]$address.code -ne 0) {
        throw "Create address for $username failed: $($address | ConvertTo-Json -Compress)"
    }

    $rows.Add([PSCustomObject]@{
            username = $username
            password = $Password
        })

    Write-Host ("[{0}/{1}] prepared {2}" -f $index, $UserCount, $username)
}

$rows | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8
$setupFile = Join-Path (Split-Path -Parent $OutputFile) 'setup-user.csv'
$rows | Select-Object -First 1 | Export-Csv -LiteralPath $setupFile -NoTypeInformation -Encoding UTF8
Write-Host "User CSV generated: $OutputFile"
Write-Host "Setup user CSV generated: $setupFile"
