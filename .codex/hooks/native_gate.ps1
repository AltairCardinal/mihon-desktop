param(
    [string]$PythonPath = 'C:/Users/feeli/AppData/Local/Programs/Python/Python314/python.exe',
    [string]$ScriptPath = (Join-Path $PSScriptRoot 'native_gate.py'),
    [string]$LogPath = $env:CODEX_NATIVE_HOOK_LOG,
    [ValidateRange(50, 60000)][int]$TimeoutMs = 5000,
    [ValidateRange(1024, 10485760)][int]$LogMaxBytes = 262144,
    [ValidateRange(1, 5)][int]$LogFileCount = 3
)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$runId = [Guid]::NewGuid().ToString('N')
$clock = [Diagnostics.Stopwatch]::StartNew()
$childPid = $null
$stage = 'initialize'
$status = 0
$p = $null
$started = $false
$exitCode = $null
if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path $env:LOCALAPPDATA 'Codex/hooks/native-gate.jsonl'
}

function Write-HookLog([string]$Event, [string]$Level, [string]$Category, [string]$Message) {
    $mutex = $null
    $owned = $false
    try {
        $absolute = [IO.Path]::GetFullPath($LogPath)
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $digest = [BitConverter]::ToString($sha.ComputeHash($utf8.GetBytes($absolute.ToUpperInvariant()))).Replace('-', '') }
        finally { $sha.Dispose() }
        $mutex = New-Object Threading.Mutex($false, ('Local\CodexNativeGate_' + $digest))
        try { $owned = $mutex.WaitOne(200) }
        catch [Threading.AbandonedMutexException] { $owned = $true }
        if (-not $owned) { throw 'Log lock busy' }
        $entry = [ordered]@{
            utc = [DateTime]::UtcNow.ToString('o'); runId = $runId; pid = $PID
            childPid = $childPid; event = $Event; level = $Level; stage = $stage
            elapsedMs = $clock.ElapsedMilliseconds; exitCode = $exitCode
            category = $Category; message = $Message
        }
        $line = ($entry | ConvertTo-Json -Compress) + "`n"
        $bytes = $utf8.GetByteCount($line)
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($absolute)) | Out-Null
        if ([IO.File]::Exists($absolute) -and (([IO.FileInfo]$absolute).Length + $bytes -gt $LogMaxBytes)) {
            if ($LogFileCount -eq 1) { [IO.File]::Delete($absolute) }
            else {
                $oldest = $absolute + '.' + ($LogFileCount - 1)
                if ([IO.File]::Exists($oldest)) { [IO.File]::Delete($oldest) }
                for ($i = $LogFileCount - 2; $i -ge 1; $i--) {
                    if ([IO.File]::Exists($absolute + '.' + $i)) { [IO.File]::Move(($absolute + '.' + $i), ($absolute + '.' + ($i + 1))) }
                }
                [IO.File]::Move($absolute, ($absolute + '.1'))
            }
        }
        [IO.File]::AppendAllText($absolute, $line, $utf8)
    } catch {
        [Console]::Error.WriteLine('Hook log unavailable; gate result unchanged.')
    } finally {
        if ($owned) { try { $mutex.ReleaseMutex() } catch { [Console]::Error.WriteLine('Hook log unavailable; gate result unchanged.') } }
        if ($null -ne $mutex) {
            try { $mutex.Dispose() } catch { [Console]::Error.WriteLine('Hook log unavailable; gate result unchanged.') }
        }
    }
}

function Wait-HookTask($Task) {
    $remaining = $TimeoutMs - [int]$checkClock.ElapsedMilliseconds
    if ($remaining -le 0 -or -not $Task.Wait($remaining)) { throw [TimeoutException]::new('Hook timed out') }
}

Write-HookLog 'check_started' 'info' 'started' 'Hook check started.'
$checkClock = [Diagnostics.Stopwatch]::StartNew()
try {
    $stage = 'preflight'
    if (-not [IO.File]::Exists($ScriptPath)) { throw [IO.FileNotFoundException]::new('script_missing') }
    if (-not [IO.File]::Exists($PythonPath)) { throw [IO.FileNotFoundException]::new('python_missing') }
    $stage = 'input'
    # Console.In is a synchronized TextReader on some hosts; its Async method
    # can block before returning. Use an unsynchronized reader over the pipe.
    $inputReader = New-Object IO.StreamReader([Console]::OpenStandardInput(), $utf8, $false, 4096, $true)
    $inputTask = $inputReader.ReadToEndAsync()
    Wait-HookTask $inputTask
    $payload = $inputTask.Result
    $inputReader.Dispose()
    $stage = 'start'
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $PythonPath
    $info.Arguments = '-X utf8 "' + $ScriptPath + '" --wrapped'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    $info.EnvironmentVariables['PYTHONUTF8'] = '1'
    $info.EnvironmentVariables['PYTHONIOENCODING'] = 'utf-8'
    $info.EnvironmentVariables['PYTHONDONTWRITEBYTECODE'] = '1'
    $p = New-Object Diagnostics.Process
    $p.StartInfo = $info
    if (-not $p.Start()) { throw 'start_failed' }
    $started = $true
    $childPid = $p.Id
    # Drain both pipes before writing stdin; bound every task wait.
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $stage = 'stdin'
    try {
        Wait-HookTask ($p.StandardInput.WriteAsync($payload))
        Wait-HookTask ($p.StandardInput.FlushAsync())
        $p.StandardInput.Close()
    } catch {
        # An early Python error can close stdin before the write (notably PS5).
        # Preserve its actual exit code and stderr instead of hiding that error.
        if ($_.Exception -is [TimeoutException] -or -not $p.HasExited) { throw }
    }
    $stage = 'wait'
    $remaining = $TimeoutMs - [int]$checkClock.ElapsedMilliseconds
    if ($remaining -le 0 -or -not $p.WaitForExit($remaining)) { throw [TimeoutException]::new('Hook timed out') }
    $exitCode = $p.ExitCode
    $stage = 'output'
    Wait-HookTask $outTask
    Wait-HookTask $errTask
    [Console]::Out.Write($outTask.Result)
    [Console]::Error.Write($errTask.Result)
    $stage = 'complete'
    if ($exitCode -eq 42) {
        $status = 2
        Write-HookLog 'check_denied' 'info' 'policy_denied' 'Command rejected by policy.'
    } elseif ($exitCode -eq 43) {
        Write-HookLog 'check_failed_open' 'warn' 'invalid_input' 'Invalid input; command allowed.'
    } elseif ($exitCode -ne 0) {
        Write-HookLog 'check_failed_open' 'error' 'unexpected_exit' 'Checker failed; command allowed.'
    } else {
        Write-HookLog 'check_allowed' 'info' 'allowed' 'Command allowed.'
    }
} catch {
    $category = 'execution_failed'
    if ($_.Exception -is [TimeoutException]) { $category = 'timeout' }
    elseif ($stage -eq 'preflight') {
        if (-not [IO.File]::Exists($ScriptPath)) { $category = 'script_missing' } else { $category = 'python_missing' }
    } elseif ($stage -eq 'start') { $category = 'start_failed' }
    [Console]::Error.WriteLine('Hook check failed; allowing command.')
    Write-HookLog 'check_failed_open' 'error' $category 'Checker failed; command allowed.'
} finally {
    if ($null -ne $p) {
        $stage = 'cleanup'
        try {
            if ($started -and -not $p.HasExited) {
                $p.Kill()
                if (-not $p.WaitForExit(500)) { throw 'cleanup_timeout' }
            }
        } catch {
            [Console]::Error.WriteLine('Hook child cleanup failed.')
            Write-HookLog 'cleanup_failed' 'error' 'cleanup_failed' 'Child cleanup failed.'
        } finally {
            try { $p.Dispose() } catch {
                [Console]::Error.WriteLine('Hook child cleanup failed.')
                Write-HookLog 'cleanup_failed' 'error' 'dispose_failed' 'Process handle cleanup failed.'
            }
        }
    }
}
exit $status
