param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = 'superadmin',
    # 与 prepare-users.ps1 同样的理由：不设默认口令
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [string]$OutputFile = (Join-Path $PSScriptRoot 'admin-token.csv')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 秒杀库存预热自阶段九起收归平台管理端：它会把 Redis 库存重置为数据库快照，
# 挂在 C 端路径上时任何注册用户都能调用，秒杀进行中调用是超卖路径。
# 因此压测脚本不能再拿普通用户 token 去预热。
$BaseUrl = $BaseUrl.TrimEnd('/')

$body = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress

try {
    $login = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/api/admin/login" `
        -ContentType 'application/json' `
        -Body $body
}
catch {
    throw "Admin login failed: $($_.Exception.Message)"
}

if ([int]$login.code -ne 0 -or $null -eq $login.data.accessToken) {
    throw "Admin login failed: $($login | ConvertTo-Json -Compress)"
}

if ($login.data.role -ne 'SUPER_ADMIN') {
    throw "Account '$Username' is not a platform admin (role=$($login.data.role))"
}

# 1) 给只发预热请求的计划用：只要 token
[PSCustomObject]@{ adminToken = $login.data.accessToken } |
    Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8

# 2) 给自带登录步骤的计划用：用户名口令，由计划自己换 token
$credentialFile = Join-Path (Split-Path -Parent $OutputFile) 'admin-credentials.csv'
[PSCustomObject]@{ adminUsername = $Username; adminPassword = $Password } |
    Export-Csv -LiteralPath $credentialFile -NoTypeInformation -Encoding UTF8

Write-Host "Admin token CSV generated: $OutputFile"
Write-Host "Admin credential CSV generated: $credentialFile"
