# Read-only host verification; does not build, install, launch or rewrite an APK.
param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$Sdk,
    [Parameter(Mandatory=$true)][string]$Json,
    [string]$Ndk = "28.2.13676358",
    [string]$BuildTools = "37.0.0"
)
& py -3 "$PSScriptRoot/verify_native.py" --apk $Apk --sdk $Sdk --ndk $Ndk --build-tools $BuildTools --json $Json
exit $LASTEXITCODE
