param([ValidateSet('build','produce','consume','scheduler','pull','close')][string]$Phase)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../../../..").Path
$scope=Join-Path $repo 'app/desktopApp/build/p11-moments-poc-20261022'
$sdk='C:/Users/1/AppData/Local/Android/Sdk'
$env:ANDROID_HOME=$sdk
$env:ANDROID_SDK_ROOT=$sdk
$env:ANDROID_USER_HOME=Join-Path $scope 'android-home'
$env:ANDROID_AVD_HOME=Join-Path $scope 'avds'
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:CHATBAR_SILICONFLOW_API_KEY=''
$env:CHATBAR_SUPABASE_URL=''
$env:CHATBAR_SUPABASE_ANON_KEY=''
$run=Join-Path $scope ('raw/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+$Phase)
New-Item -ItemType Directory -Path $run | Out-Null
$adb=Join-Path $sdk 'platform-tools/adb.exe'
function Invoke-Recorded([string]$Exe,[string[]]$Arguments,[string]$Name) {
    @{executable=$Exe;arguments=$Arguments;workingDirectory=(Get-Location).Path} | ConvertTo-Json -Depth 5 | Set-Content "$run/$Name-command.json"
    & $Exe @Arguments 2>&1 | Tee-Object "$run/$Name-output.log"
    $code=$LASTEXITCODE
    @{exitCode=$code} | ConvertTo-Json | Set-Content "$run/$Name-result.json"
    if($code -ne 0) { throw "$Name exited $code" }
}
if($Phase -eq 'build') {
    $test=Join-Path $scope 'official/app/app/src/androidTest/java/com/example/chatbar/domain/backup'
    New-Item -ItemType Directory -Force -Path $test | Out-Null
    Copy-Item -LiteralPath "$PSScriptRoot/OfficialMomentsPocTest.kt" -Destination "$test/OfficialMomentsPocTest.kt"
    Push-Location "$scope/official/app"
    try {
        Invoke-Recorded '.\gradlew.bat' @(':app:assembleDebug',':app:assembleDebugAndroidTest','--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','-Pkotlin.compiler.execution.strategy=in-process','-PCHATBAR_SILICONFLOW_API_KEY=','-PCHATBAR_SUPABASE_URL=','-PCHATBAR_SUPABASE_ANON_KEY=') 'build'
    } finally { Pop-Location }
} else {
    # Dedicated server + exact newly created AVD; never select a physical/default device.
    $prefix=@('-P','5045','-s','emulator-5584')
    if($Phase -ne 'close') {
        $name=& $adb @prefix emu avd name
        if($LASTEXITCODE -ne 0 -or ($name -notcontains 'ccb-p11-moments-poc-20261022')) { throw 'OWNED_AVD_IDENTITY_REQUIRED' }
    }
    if($Phase -in @('produce','consume','scheduler')) {
        $method=@{produce='produceOfficialArchive';consume='consumeReexportThroughOfficialAndRepository';scheduler='restoredTasksAndActualSchedulerAdmission'}[$Phase]
        Invoke-Recorded $adb ($prefix+@('shell','am','instrument','-w','-r','-e','listener','com.example.chatbar.domain.backup.PocXmlListener','-e','class',"com.example.chatbar.domain.backup.OfficialMomentsPocTest#$method",'com.example.chatbar.test/androidx.test.runner.AndroidJUnitRunner')) 'instrumentation'
        if(-not (Select-String -LiteralPath "$run/instrumentation-output.log" -SimpleMatch 'OK (1 test)' -Quiet)) { throw 'INSTRUMENTATION_NOT_PASSED' }
    } elseif($Phase -eq 'pull') {
        Invoke-Recorded $adb ($prefix+@('pull','/sdcard/Android/data/com.example.chatbar/files/p11-moments-poc',"$run/artifacts")) 'pull'
    } elseif($Phase -eq 'close') {
        $name=& $adb @prefix emu avd name
        if($LASTEXITCODE -eq 0 -and ($name -contains 'ccb-p11-moments-poc-20261022')) {
            Invoke-Recorded $adb ($prefix+@('emu','kill')) 'emulator-close'
        } else { throw 'OWNED_AVD_IDENTITY_REQUIRED' }
    }
}
Write-Output "Evidence: $run"
