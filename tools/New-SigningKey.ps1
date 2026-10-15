#requires -Version 5.1
param(
    [string]$Destination = (Join-Path $env:USERPROFILE 'AnhuaTV-signing-v1'),
    [string]$KeytoolPath = ''
)
$ErrorActionPreference = 'Stop'

function PlainText([Security.SecureString]$Secure) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

if (Test-Path $Destination) {
    throw 'Destination already exists. Keep your existing key. To make an independent key, choose a new -Destination folder.'
}
if (-not $KeytoolPath) {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { $KeytoolPath = $command.Source }
}
if (-not $KeytoolPath -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path $candidate) { $KeytoolPath = $candidate }
}
if (-not $KeytoolPath) {
    $searchRoots = @(
        "$env:ProgramFiles\Eclipse Adoptium\*\bin\keytool.exe",
        "$env:ProgramFiles\Java\*\bin\keytool.exe",
        "$env:ProgramFiles\Microsoft\jdk-*\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe"
    )
    foreach ($pattern in $searchRoots) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $KeytoolPath = $found.FullName; break }
    }
}
if (-not $KeytoolPath -or -not (Test-Path $KeytoolPath)) {
    throw 'keytool was not found. Install a JDK 17, reopen PowerShell, then run this script again. See the Chinese guide.'
}
Write-Host 'Create a NEW signing key for AnhuaTV 1.0. Do not enter your GitHub password.'
$secure = Read-Host 'New signing password (at least 8 ASCII characters)' -AsSecureString
$confirmSecure = Read-Host 'Repeat the same signing password' -AsSecureString
$password = PlainText $secure
$confirmation = PlainText $confirmSecure
if ($password -ne $confirmation) { throw 'The two passwords do not match.' }
if ($password.Length -lt 8 -or $password -notmatch '^[\x21-\x7E]+$') {
    throw 'Use at least 8 ASCII characters, with no spaces. Save it in your password manager.'
}
$destinationPath = [IO.Path]::GetFullPath($Destination)
$null = New-Item -ItemType Directory -Path $destinationPath
$keyFile = Join-Path $destinationPath 'anhua-tv-v1.p12'
try {
    $env:TV_TEMP_KEY_PASSWORD = $password
    & $KeytoolPath -genkeypair -keystore $keyFile -storetype PKCS12 `
        -alias 'anhua-tv-v1' -keyalg RSA -keysize 3072 -validity 10000 `
        -storepass:env TV_TEMP_KEY_PASSWORD -keypass:env TV_TEMP_KEY_PASSWORD `
        -dname 'CN=Anhua TV, OU=Personal TV, O=Personal, C=CN' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed. No signing key was finalized.' }
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyFile))
    [IO.File]::WriteAllText((Join-Path $destinationPath 'TV_KEYSTORE_BASE64.txt'), $base64, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $destinationPath 'TV_KEY_ALIAS.txt'), 'anhua-tv-v1', [Text.UTF8Encoding]::new($false))
    $instructions = @'
AnhuaTV 1.0 signing backup. Keep this folder OUTSIDE the code repository.

TV_KEYSTORE_BASE64 = full content of TV_KEYSTORE_BASE64.txt
TV_KEY_ALIAS = anhua-tv-v1
TV_STORE_PASSWORD = the new password you just entered
TV_KEY_PASSWORD = exactly the same new password

The password is deliberately NOT written to this folder. Save it separately.
The .p12 file is the private signing key; base64 is another representation of it.
For every future version, reuse this exact key and password. Do NOT regenerate.
Do not publish this folder, email it, or upload it as repository source files.
'@
    [IO.File]::WriteAllText((Join-Path $destinationPath 'BACKUP-README.txt'), $instructions, [Text.UTF8Encoding]::new($false))
    Write-Host ''
    Write-Host "Created signing backup: $destinationPath"
    Write-Host 'Use the two text files and your saved password to create four Repository Secrets.'
    Write-Host 'TV_STORE_PASSWORD and TV_KEY_PASSWORD use the SAME password in this new project.'
    Write-Host 'Do not upload the signing backup folder to your repository.'
} finally {
    Remove-Item Env:\TV_TEMP_KEY_PASSWORD -ErrorAction SilentlyContinue
    $password = $null; $confirmation = $null; $base64 = $null
    $secure.Dispose(); $confirmSecure.Dispose()
}
