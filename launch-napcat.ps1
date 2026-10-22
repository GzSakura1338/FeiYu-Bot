Start-Process cmd.exe -ArgumentList "/k",".\launcher-user.bat" -WorkingDirectory (Join-Path $PSScriptRoot "napcat")
