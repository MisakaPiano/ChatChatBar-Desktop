param([string]$Number='903')
$ErrorActionPreference='Stop'
if($Number -notmatch '^90[3-9]$'){throw 'SYNTHETIC_BUILD_LABEL_REQUIRED'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$out=Join-Path $repo "docs/desktop/evidence/prepreview-autonomous-20261023/raw/package-$Number"
$build=Join-Path $repo "app/desktopApp/build/prepreview-packages/build-$Number"
if((Test-Path -LiteralPath $out) -or (Test-Path -LiteralPath $build)){throw 'OWNED_BUILD_ALREADY_EXISTS'}
New-Item -ItemType Directory $out | Out-Null
$forward=$build.Replace('\','/')
$init=Join-Path $out 'package.init.gradle'
"gradle.beforeProject { p -> if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('$forward')) }" | Set-Content $init
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$argsList=@('--offline','--no-daemon','--no-build-cache','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',
 "-PdesktopVersion=0.1.0-preview.$Number",'-I',$init,':desktopApp:createDistributable')
$argsList | ConvertTo-Json | Set-Content (Join-Path $out 'arguments.json')
Push-Location (Join-Path $repo 'app')
try{
 & ./gradlew.bat @argsList *> (Join-Path $out 'build.log')
 $code=$LASTEXITCODE;$code | Set-Content (Join-Path $out 'exit.txt')
 "PACKAGE=$Number EXIT=$code";exit $code
}finally{Pop-Location}
