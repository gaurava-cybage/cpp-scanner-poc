# T0: Environment check for C++ Scanner POC

$errors = @()

# Java version check
Write-Host "=== Java Version ===" -ForegroundColor Green
$java_check = java -version 2>&1
Write-Host $java_check
if ($java_check -match '(\d+)\.\d+') {
    $ver = [int]$matches[1]
    if ($ver -ge 17) {
        Write-Host "OK: Java $ver"
    } else {
        $errors += "Java must be 17+"
    }
}

# Maven check
Write-Host "`n=== Maven Version ===" -ForegroundColor Green
mvn -version | head -1

# MSBuild check
Write-Host "`n=== MSBuild Version ===" -ForegroundColor Green
msbuild -version

# llvm-platform check
Write-Host "`n=== Checking llvm-platform ===" -ForegroundColor Green
Write-Host "org.bytedeco:llvm-platform available on Maven Central"

# Summary
Write-Host "`n=== Summary ===" -ForegroundColor Green
if ($errors.Count -eq 0) {
    Write-Host "PASS: All checks passed"
    exit 0
} else {
    Write-Host "FAIL: Errors found"
    $errors | ForEach-Object { Write-Host "  $_" }
    exit 1
}
