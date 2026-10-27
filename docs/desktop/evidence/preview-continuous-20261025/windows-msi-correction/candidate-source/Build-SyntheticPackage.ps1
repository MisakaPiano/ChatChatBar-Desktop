param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]{1,24}$')][string]$BuildId,
    [Parameter(Mandatory=$true)][ValidateRange(1000,1999)][int]$Sequence,
    [ValidateSet('Image','Msi')][string]$Format='Image'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
git -C $repo diff --quiet
if($LASTEXITCODE -ne 0){throw 'TRACKED_SOURCE_DIRTY'}
git -C $repo diff --cached --quiet
if($LASTEXITCODE -ne 0){throw 'STAGED_SOURCE_DIRTY'}
$head=git -C $repo rev-parse HEAD
$build=Join-Path $repo "app/desktopApp/build/preview-win-$BuildId"
$evidence=Join-Path $repo "app/desktopApp/build/preview-continuous-private-20261025/windows-package-$BuildId"
if((Test-Path -LiteralPath $build) -or (Test-Path -LiteralPath $evidence)){throw 'NEW_TASK_DIRECTORIES_REQUIRED'}
New-Item -ItemType Directory -Path $build,$evidence | Out-Null
@{kind='CCB_DESKTOP_SYNTHETIC_PACKAGE';sourceCommit=$head;sequence=$Sequence;format=$Format} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $build 'TASK_OWNER.json') -Encoding UTF8
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $evidence 'Build-SyntheticPackage.ps1')
[IO.File]::WriteAllText((Join-Path $evidence 'head.txt'),$head)
$inputRows=@()
$inputs=git -C $repo -c core.quotepath=false ls-files app/desktopApp/src/main app/sharedCore/src/main app/app/src/main/java app/app/src/main/assets app/desktopApp/build.gradle.kts app/sharedCore/build.gradle.kts app/settings.gradle.kts app/gradle/libs.versions.toml
foreach($name in $inputs){
    $file=Join-Path $repo $name
    $item=Get-Item -LiteralPath $file
    if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'BUILD_INPUT_REPARSE_REFUSED'}
    $inputRows+=@{path=$name;bytes=$item.Length;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$inputRows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'BUILD_INPUTS.json') -Encoding UTF8
$forward=$build.Replace('\','/')
$init=Join-Path $evidence 'package.init.gradle'
"gradle.beforeProject { p -> if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('$forward')) }" |
    Set-Content -LiteralPath $init -Encoding UTF8
$packageTask=if($Format -eq 'Image'){':desktopApp:createDistributable'}else{':desktopApp:packageMsi'}
$arguments=@('--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
    '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',
    "-PdesktopVersion=0.1.0-preview.$Sequence","-PdesktopInstallerVersion=0.1.$Sequence",'-PdesktopPackagingProfile=synthetic',
    '-I',$init,':desktopApp:verifyDesktopInstallerIdentity',$packageTask)
$arguments | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'arguments.json') -Encoding UTF8
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
Push-Location (Join-Path $repo 'app')
try {
    & ./gradlew.bat @arguments *> (Join-Path $evidence 'build.log')
    $result=$LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $evidence 'exit.txt'),[string]$result)
    if($result -ne 0){Get-Content -LiteralPath (Join-Path $evidence 'build.log') -Tail 25;exit $result}
} finally {Pop-Location}
$image=Join-Path $build 'compose/binaries/main/app/ChatChatBarDesktopSynthetic'
if($Format -eq 'Msi'){
    $msi=Join-Path $build "compose/binaries/main/msi/ChatChatBarDesktopSynthetic-0.1.$Sequence.msi"
    if(-not(Test-Path -LiteralPath $msi -PathType Leaf)){throw 'SYNTHETIC_MSI_MISSING'}
    $pinned=Join-Path $evidence "ChatChatBarDesktopSynthetic-0.1.$Sequence.msi"
    $sourceStream=[IO.File]::OpenRead($msi)
    try {
        $pinnedStream=[IO.File]::Open($pinned,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$sourceStream.CopyTo($pinnedStream);$pinnedStream.Flush($true)} finally {$pinnedStream.Dispose()}
    } finally {$sourceStream.Dispose()}
    if((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $pinned -Algorithm SHA256).Hash){throw 'PINNED_MSI_BYTES_CHANGED'}
    @{bytes=(Get-Item -LiteralPath $pinned).Length;sha256=(Get-FileHash -LiteralPath $pinned -Algorithm SHA256).Hash.ToLowerInvariant();sourceCommit=$head} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'PINNED_MSI.json') -Encoding UTF8
    Write-Output "SYNTHETIC_MSI_BUILT_AND_PINNED source=$head sequence=$Sequence"
    exit 0
}
if(-not(Test-Path -LiteralPath $image -PathType Container)){throw 'SYNTHETIC_APPLICATION_IMAGE_MISSING'}
$rows=@()
foreach($file in Get-ChildItem -LiteralPath $image -File -Recurse){
    if(($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'PACKAGE_REPARSE_REFUSED'}
    if($file.Extension -in @('.cbbackup','.cbback','.cbsave','.ccbsecret','.protected')){throw 'PRIVATE_ARCHIVE_OR_SECRET_IN_PACKAGE'}
    $rows+=@{path=$file.FullName.Substring($image.Length+1).Replace('\','/');bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'PACKAGE_FILES.json') -Encoding UTF8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$identity=$null
foreach($jar in Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter '*.jar' -File){
    $zip=[IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $entry=$zip.GetEntry('desktop-build.properties')
        if($entry){
            if($null -ne $identity){throw 'DUPLICATE_PACKAGED_BUILD_IDENTITY'}
            $reader=[IO.StreamReader]::new($entry.Open())
            try{$identity=$reader.ReadToEnd()}finally{$reader.Dispose()}
        }
    } finally {$zip.Dispose()}
}
if($null -eq $identity){throw 'PACKAGED_BUILD_IDENTITY_MISSING'}
$expected=@("version=0.1.0-preview.$Sequence","commit=$head","installerVersion=0.1.$Sequence",'installerVersionAssigned=true','packagingProfile=synthetic')
foreach($line in $expected){if($line -notin ($identity -split "`n" | ForEach-Object {$_.TrimEnd("`r")})){throw 'PACKAGED_BUILD_IDENTITY_MISMATCH'}}
[IO.File]::WriteAllText((Join-Path $evidence 'PACKAGED_BUILD_IDENTITY.properties'),$identity)
Write-Output "SYNTHETIC_PACKAGE_VERIFIED source=$head files=$($rows.Count) format=$Format"
