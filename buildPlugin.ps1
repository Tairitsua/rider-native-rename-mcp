Param(
    $Version = "1.0.0",
    [string]$Configuration = "Release"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSScriptRoot = Split-Path $MyInvocation.MyCommand.Path -Parent
Set-Location $PSScriptRoot

. ".\settings.ps1"

Invoke-Exe $MSBuildPath "/t:Restore;Rebuild;Pack" "$SolutionPath" "/v:minimal" "/p:Configuration=$Configuration" "/p:PackageVersion=$Version" "/p:PackageOutputPath=`"$OutputDirectory`""
