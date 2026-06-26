using System.Diagnostics;

const string ServiceName = "PcAgent";
const string FirewallRuleName = "PcAgent Inbound";

// A running exe can't delete its own containing folder, and this normally runs from
// inside the install directory it needs to remove. So: copy ourselves to %TEMP%, relaunch
// from there (no extra UAC prompt - we already inherit this process's elevated token), and
// let that copy do the actual work once this original process (and its file lock) is gone.
if (args.Length >= 2 && args[0] == "--cleanup")
{
    RunCleanup(installDir: args[1], parentPid: int.Parse(args[2]));
    return;
}

RelaunchFromTempAndExit();

static void RelaunchFromTempAndExit()
{
    var installDir = AppContext.BaseDirectory.TrimEnd('\\');
    var tempCopy = Path.Combine(Path.GetTempPath(), $"PcAgentUninstall-{Guid.NewGuid():N}.exe");
    File.Copy(Environment.ProcessPath!, tempCopy, overwrite: true);

    var psi = new ProcessStartInfo(tempCopy)
    {
        UseShellExecute = false,
        // Without this, the child inherits our CWD (Explorer sets a double-clicked exe's
        // CWD to its own folder) - and Windows refuses to remove a directory that's any
        // process's current working directory, even after the file itself is unlocked.
        WorkingDirectory = Path.GetTempPath()
    };
    psi.ArgumentList.Add("--cleanup");
    psi.ArgumentList.Add(installDir);
    psi.ArgumentList.Add(Environment.ProcessId.ToString());
    Process.Start(psi);
}

static void RunCleanup(string installDir, int parentPid)
{
    Environment.CurrentDirectory = Path.GetTempPath();

    Console.WriteLine("================================================");
    Console.WriteLine(" MyHomePC PC Agent - Uninstall");
    Console.WriteLine("================================================");
    Console.WriteLine();

    try
    {
        Process.GetProcessById(parentPid).WaitForExit(5000);
    }
    catch (ArgumentException)
    {
        // already exited
    }

    if (ServiceExists(ServiceName))
    {
        Console.WriteLine("Stopping and removing the service...");
        RunProcess("sc.exe", "stop", ServiceName);
        Thread.Sleep(2000);
        RunProcess("sc.exe", "delete", ServiceName);
    }
    else
    {
        Console.WriteLine("Service is not installed - nothing to stop.");
    }

    Console.WriteLine("Removing firewall rule...");
    RunProcess("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FirewallRuleName}");

    Console.WriteLine($"Removing {installDir}...");
    var removed = DeleteDirectoryWithRetry(installDir);

    Console.WriteLine();
    Console.WriteLine(removed
        ? "Done. PcAgent has been fully removed."
        : $"Done, but {installDir} could not be fully deleted (still in use?) - remove it yourself if needed.");
    Pause();

    SelfDestruct();
}

static bool DeleteDirectoryWithRetry(string dir)
{
    if (!Directory.Exists(dir))
    {
        return true;
    }

    for (var attempt = 1; attempt <= 10; attempt++)
    {
        try
        {
            Directory.Delete(dir, recursive: true);
            return true;
        }
        catch (Exception ex) when (attempt < 10 && (ex is IOException or UnauthorizedAccessException))
        {
            Thread.Sleep(500);
        }
    }

    return !Directory.Exists(dir);
}

static void SelfDestruct()
{
    // Deletes this temp copy a moment after we exit, so %TEMP% doesn't accumulate one of these per run.
    // Uses single quotes for the path inside the PowerShell script string so it doesn't collide with
    // ArgumentList's own double-quote escaping (a cmd.exe "del" version of this had exactly that bug).
    var psi = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true };
    psi.ArgumentList.Add("-NoProfile");
    psi.ArgumentList.Add("-Command");
    psi.ArgumentList.Add($"Start-Sleep -Seconds 1; Remove-Item -Force -LiteralPath '{Environment.ProcessPath}'");
    Process.Start(psi);
}

static bool ServiceExists(string serviceName)
{
    var psi = new ProcessStartInfo("sc.exe") { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true };
    psi.ArgumentList.Add("query");
    psi.ArgumentList.Add(serviceName);
    using var process = Process.Start(psi)!;
    process.WaitForExit();
    return process.ExitCode == 0;
}

static int RunProcess(string fileName, params string[] arguments)
{
    var psi = new ProcessStartInfo(fileName) { UseShellExecute = false };
    foreach (var a in arguments)
    {
        psi.ArgumentList.Add(a);
    }
    using var process = Process.Start(psi)!;
    process.WaitForExit();
    return process.ExitCode;
}

static void Pause()
{
    Console.WriteLine();
    Console.WriteLine("Press Enter to close.");
    Console.ReadLine();
}
