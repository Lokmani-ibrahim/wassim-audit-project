
param(
    [string]$ApiBase = "http://localhost:8082",
    [string]$KeycloakBase = "http://localhost:8081",
    [string]$Realm = "neos",
    [string]$ClientId = "neos-dev-cli",
    [string]$SourceCurrency = "EUR",
    [string]$DestinationCurrency = "USD",
    [long]$SourceMinor = 10000
)

$ErrorActionPreference = "Stop"

$ApiBase = $ApiBase.TrimEnd("/")
$KeycloakBase = $KeycloakBase.TrimEnd("/")

# Safety: this script is ONLY for local demo services.
foreach ($url in @($ApiBase, $KeycloakBase)) {
    $uri = [uri]$url

    if (-not $uri.IsLoopback -or $uri.Scheme -ne "http") {
        throw "Only local HTTP demo services are permitted: $url"
    }
}

$journalPath = Join-Path `
    ([System.IO.Path]::GetTempPath()) `
    "neos-fx-demo-last.json"

function Save-DemoJournal {
    param([hashtable]$Data)

    $Data | ConvertTo-Json -Depth 8 |
        Set-Content -Path $journalPath -Encoding UTF8
}

function Get-Wallet {
    param(
        [object]$Response,
        [string]$Currency
    )

    $wallet = @($Response.wallets) |
        Where-Object { $_.currency -eq $Currency } |
        Select-Object -First 1

    if ($null -eq $wallet) {
        throw "Wallet $Currency was not found in GET /api/wallets"
    }

    return $wallet
}

function Show-Balances {
    param(
        [object]$Response,
        [string]$Title
    )

    Write-Host "`n========== $Title ==========" -ForegroundColor Cyan

    foreach ($wallet in @($Response.wallets)) {
        Write-Host (
            "{0,-4} | Available: {1,-16} | Pending: {2,-12} | Status: {3}" -f `
            $wallet.currency, `
            $wallet.availableMinor, `
            $wallet.pendingMinor, `
            $wallet.status
        )
    }
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "       NEOS FX END-TO-END DEMO" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Backend:      $ApiBase"
Write-Host "Keycloak:     $KeycloakBase"
Write-Host "Client:       $ClientId"
Write-Host "Conversion:   $SourceCurrency -> $DestinationCurrency"
Write-Host "Source minor: $SourceMinor"
Write-Host "Mode:         SIMULATED BANK + REAL LOCAL FORMANCE"
Write-Host ""

if ($SourceMinor -le 0) {
    throw "SourceMinor must be positive"
}

# --------------------------------------------------
# STEP 1: AUTOMATIC KEYCLOAK TOKEN
# --------------------------------------------------

Write-Host "[1/6] Authenticating with Keycloak..." -ForegroundColor Yellow

$credential = Get-Credential `
    -Message "Enter your NEOS TEST CUSTOMER credentials"

if ($null -eq $credential) {
    throw "Authentication cancelled"
}

$tokenEndpoint = "$KeycloakBase/realms/$Realm/protocol/openid-connect/token"

$passwordPointer = [IntPtr]::Zero

try {
    $passwordPointer = `
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
            $credential.Password
        )

    $plainPassword = `
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
            $passwordPointer
        )

    $tokenResponse = Invoke-RestMethod `
        -Uri $tokenEndpoint `
        -Method Post `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "password"
            client_id  = $ClientId
            username   = $credential.UserName
            password   = $plainPassword
            scope      = "openid"
        } `
        -TimeoutSec 20
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(
            $passwordPointer
        )
    }

    $plainPassword = $null
    $credential = $null
}

if (-not $tokenResponse.access_token) {
    throw "Keycloak did not return an access token"
}

$headers = @{
    Authorization = "Bearer $($tokenResponse.access_token)"
}

Write-Host "OK - Access token obtained" -ForegroundColor Green

# --------------------------------------------------
# STEP 2: VALIDATE CUSTOMER IDENTITY
# --------------------------------------------------

Write-Host "`n[2/6] Checking authenticated customer..." -ForegroundColor Yellow

$me = Invoke-RestMethod `
    -Uri "$ApiBase/api/auth/me" `
    -Method Get `
    -Headers $headers `
    -TimeoutSec 20

Write-Host "OK - Customer authenticated" -ForegroundColor Green

$me | ConvertTo-Json -Depth 6

Write-Host "`n=== CUSTOMER KYC ===" -ForegroundColor Cyan

$kyc = Invoke-RestMethod `
    -Method Post `
    -Uri "$ApiBase/api/kyc/start" `
    -Headers $headers `
    -TimeoutSec 20

Write-Host "NEOS user ID: $($kyc.userId)"
Write-Host "KYC status:   $($kyc.status)"
Write-Host "Provider:     $($kyc.provider)"
# --------------------------------------------------
# STEP 3: READ REAL FORMANCE WALLET BALANCES
# --------------------------------------------------

Write-Host "`n[3/6] Fetching wallet balances..." -ForegroundColor Yellow

$before = Invoke-RestMethod `
    -Uri "$ApiBase/api/wallets" `
    -Method Get `
    -Headers $headers `
    -TimeoutSec 20

Show-Balances -Response $before -Title "BEFORE CONVERSION"

$sourceWallet = Get-Wallet $before $SourceCurrency
$destinationWallet = Get-Wallet $before $DestinationCurrency

$sourceBefore = [long]$sourceWallet.availableMinor
$destinationBefore = [long]$destinationWallet.availableMinor

Write-Host "Overall wallet status: $($before.walletsStatus)"

if ($sourceWallet.status -ne "ACTIVE") {
    throw "EUR wallet is not ACTIVE; overall status: $($before.walletsStatus)"
}

if ($destinationWallet.status -ne "ACTIVE") {
    throw "USD wallet is not ACTIVE; overall status: $($before.walletsStatus)"
}

if ($sourceBefore -lt $SourceMinor) {
    throw "Insufficient $SourceCurrency test balance. Required: $SourceMinor, available: $sourceBefore"
}

Write-Host "OK - Wallets ready and source funds available" -ForegroundColor Green

# --------------------------------------------------
# STEP 4: CREATE FX QUOTE
# --------------------------------------------------

Write-Host "`n[4/6] Requesting FX quote..." -ForegroundColor Yellow

$quoteRequestKey = [guid]::NewGuid().ToString()
$conversionRequestKey = [guid]::NewGuid().ToString()

$quoteRequest = @{
    idempotencyKey      = $quoteRequestKey
    sourceCurrency      = $SourceCurrency
    destinationCurrency = $DestinationCurrency
    sourceMinor         = "$SourceMinor"
} | ConvertTo-Json

$quote = Invoke-RestMethod `
    -Uri "$ApiBase/api/fx/quotes" `
    -Method Post `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $quoteRequest `
    -TimeoutSec 15

if (-not $quote.quoteId) {
    throw "Quote response has no quoteId"
}

if ($quote.sourceCurrency -ne $SourceCurrency -or
    $quote.destinationCurrency -ne $DestinationCurrency -or
    [string]$quote.sourceMinor -ne [string]$SourceMinor) {
    throw "Quote response differs from requested conversion"
}

Write-Host "OK - Quote created" -ForegroundColor Green
Write-Host "Quote ID:    $($quote.quoteId)"
Write-Host "Rate:        $($quote.exchangeRate)"
Write-Host "Source:      $($quote.sourceMinor) $SourceCurrency minor"
Write-Host "Destination: $($quote.destinationMinor) $DestinationCurrency minor"
Write-Host "Expires:     $($quote.expiresAt)"
Write-Host "Executable:  $($quote.executable)"

# Save operation identifiers WITHOUT credentials.
$journal = @{
    mode                   = "SIMULATED_FX_DEMO"
    quoteId                = [string]$quote.quoteId
    quoteRequestKey        = $quoteRequestKey
    conversionRequestKey   = $conversionRequestKey
    sourceCurrency         = $SourceCurrency
    destinationCurrency    = $DestinationCurrency
    sourceMinor            = [string]$SourceMinor
    destinationMinor       = [string]$quote.destinationMinor
    status                 = "QUOTE_CREATED"
    operationId            = $null
}

Save-DemoJournal $journal

# --------------------------------------------------
# STEP 5: EXECUTE FX CONVERSION
# --------------------------------------------------

Write-Host "`n[5/6] Executing conversion in Formance..." -ForegroundColor Yellow

if ([datetimeoffset]::UtcNow -ge
    [datetimeoffset]::Parse([string]$quote.expiresAt)) {
    throw "FX quote expired before conversion"
}

$conversionRequest = @{
    quoteId         = $quote.quoteId
    idempotencyKey  = $conversionRequestKey
} | ConvertTo-Json

# Save the request BEFORE submitting it.
# If the HTTP request times out, the operation may
# already have been executed. Never generate a new
# key to repeat an uncertain conversion.
$journal.status = "CONVERSION_SUBMITTED"
Save-DemoJournal $journal

try {
    $conversion = Invoke-RestMethod `
        -Uri "$ApiBase/api/fx/demo/conversions" `
        -Method Post `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $conversionRequest `
        -TimeoutSec 30
}
catch {
    Write-Host "`nCONVERSION OUTCOME UNCERTAIN" -ForegroundColor Red
    Write-Host "Do NOT submit another conversion with a new key."
    Write-Host "Quote ID: $($quote.quoteId)"
    Write-Host "Request key: $conversionRequestKey"
    Write-Host "Journal: $journalPath"

    throw
}

$journal.operationId = [string]$conversion.operationId
$journal.status = [string]$conversion.status
Save-DemoJournal $journal

Write-Host "`nConversion response:" -ForegroundColor Cyan
$conversion | ConvertTo-Json -Depth 8

if ($conversion.status -ne "SUCCEEDED") {
    Write-Warning "Conversion not confirmed. Do not retry with a different key."
    Write-Host "Operation ID: $($conversion.operationId)"
    exit 1
}

if ($conversion.simulated -ne $true) {
    throw "Unexpected result: operation is not marked simulated"
}

Write-Host "OK - Formance conversion confirmed" -ForegroundColor Green

# --------------------------------------------------
# STEP 6: VERIFY FINAL BALANCES
# --------------------------------------------------

Write-Host "`n[6/6] Verifying Formance balances..." -ForegroundColor Yellow

$after = Invoke-RestMethod `
    -Uri "$ApiBase/api/wallets" `
    -Method Get `
    -Headers $headers `
    -TimeoutSec 20

Show-Balances -Response $after -Title "AFTER CONVERSION"

$sourceAfterWallet = Get-Wallet $after $SourceCurrency
$destinationAfterWallet = Get-Wallet $after $DestinationCurrency

$sourceAfter = [long]$sourceAfterWallet.availableMinor
$destinationAfter = [long]$destinationAfterWallet.availableMinor

$actualSourceChange = $sourceAfter - $sourceBefore
$actualDestinationChange = $destinationAfter - $destinationBefore

$expectedSourceChange = -$SourceMinor
$expectedDestinationChange = [long]$quote.destinationMinor

Write-Host "`n========== FX VERIFICATION ==========" -ForegroundColor Cyan

Write-Host "$SourceCurrency change: $actualSourceChange minor"
Write-Host "$DestinationCurrency change: $actualDestinationChange minor"

$sourceCorrect = $actualSourceChange -eq $expectedSourceChange
$destinationCorrect = $actualDestinationChange -eq $expectedDestinationChange

if ($sourceCorrect -and $destinationCorrect) {
    Write-Host "`n========================================" -ForegroundColor Green
    Write-Host "     NEOS FX DEMO - SUCCESS" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green

    Write-Host "Customer authenticated: YES"
    Write-Host "FX quote generated:     YES"
    Write-Host "Conversion succeeded:   YES"
    Write-Host "Formance updated:       YES"
    Write-Host "Balances verified:      YES"
    Write-Host "Bank:                   SIMULATED"

    Write-Host "`nOperation ID: $($conversion.operationId)"
    Write-Host "Settlement: $($conversion.settlementReference)"
}
else {
    Write-Host "`nBALANCE VERIFICATION MISMATCH" -ForegroundColor Red
    Write-Host "Expected $SourceCurrency change: $expectedSourceChange"
    Write-Host "Expected $DestinationCurrency change: $expectedDestinationChange"
    Write-Host "Inspect Formance and the operation; do not repeat the conversion."
    exit 1
}

Write-Host "`nJournal saved at: $journalPath"
