$native = Add-Type -Name Console -Namespace SnowLumaLauncher -PassThru -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int handle);
[DllImport("kernel32.dll")] public static extern bool GetConsoleMode(IntPtr handle, out uint mode);
[DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr handle, uint mode);
'@
# A click in a QuickEdit console selects text and blocks every write to it, which froze SnowLuma.
$stdin = $native::GetStdHandle(-10)
$mode = 0
if ($native::GetConsoleMode($stdin, [ref]$mode)) {
    [void]$native::SetConsoleMode($stdin, ($mode -band (-bnot 0x40)) -bor 0x80)
}
$Host.UI.RawUI.WindowTitle = "SnowLuma"
& .\node.exe index.mjs
Write-Host "SnowLuma exited (code $LASTEXITCODE)"
