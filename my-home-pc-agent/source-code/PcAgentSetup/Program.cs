using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

const string ServiceName = "PcAgent";
const string InstallDir = @"C:\Program Files\PcAgent";
const string FirewallRuleName = "PcAgent Inbound";
const int FirewallPort = 8585;

var sourceDir = Path.Combine(AppContext.BaseDirectory, "PcAgent");
var uninstallerExe = Path.Combine(AppContext.BaseDirectory, "Uninstaller", "Uninstall PcAgent.exe");

Console.WriteLine("================================================");
Console.WriteLine(" MyHomePC PC Agent - Install");
Console.WriteLine("================================================");
Console.WriteLine();

if (!Directory.Exists(sourceDir) || !File.Exists(uninstallerExe))
{
    Console.WriteLine($"ERROR: expected the published agent and uninstaller next to this exe (\"{sourceDir}\" and \"{uninstallerExe}\").");
    Console.WriteLine("Rebuild this Installer folder from source-code\\: powershell -ExecutionPolicy Bypass -File .\\publish.ps1");
    Pause();
    return;
}

if (ServiceExists(ServiceName))
{
    Console.WriteLine($"Service '{ServiceName}' already exists - stopping and removing it first...");
    RunProcess("sc.exe", "stop", ServiceName);
    Thread.Sleep(2000);
    RunProcess("sc.exe", "delete", ServiceName);
    Thread.Sleep(1000);
}

Console.WriteLine($"Installing to {InstallDir}...");
CopyDirectory(sourceDir, InstallDir);
File.Copy(uninstallerExe, Path.Combine(InstallDir, "Uninstall PcAgent.exe"), overwrite: true);

var secret = PromptForSecret();
WriteSecret(secret);

Console.WriteLine("Registering Windows Service...");
RunProcess("sc.exe", "create", ServiceName, "binPath=", Path.Combine(InstallDir, "PcAgent.exe"), "start=", "auto");
RunProcess("sc.exe", "failure", ServiceName, "reset=", "86400", "actions=", "restart/5000/restart/30000/restart/60000");
RunProcess("sc.exe", "description", ServiceName, "MyHomePC agent: accepts authenticated shutdown/restart commands from the home relay.");

Console.WriteLine("Adding firewall rule...");
RunProcess("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FirewallRuleName}");
RunProcess("netsh", "advfirewall", "firewall", "add", "rule", $"name={FirewallRuleName}", "dir=in", "action=allow",
    "protocol=TCP", $"localport={FirewallPort}", "profile=private");

Console.WriteLine("Starting service...");
RunProcess("sc.exe", "start", ServiceName);

Console.WriteLine();
Console.WriteLine("================================================");
Console.WriteLine(" Setup complete!");
Console.WriteLine("================================================");
Console.WriteLine();
Console.WriteLine("Copy this secret into the relay's .env as AGENT_SHARED_SECRET (it must match exactly):");
Console.WriteLine();
Console.WriteLine($"    {secret}");
Console.WriteLine();
Console.WriteLine($"To remove this later, run: {InstallDir}\\Uninstall PcAgent.exe");
Console.WriteLine();
Pause();

static string PromptForSecret()
{
    Console.WriteLine();
    Console.WriteLine("This agent needs a shared secret so only your relay can tell it to shut down/restart this PC.");
    Console.Write("Press Enter to auto-generate a strong secret, or type your own and press Enter: ");
    var typed = Console.ReadLine();
    if (!string.IsNullOrWhiteSpace(typed))
    {
        return typed.Trim();
    }

    var generated = Convert.ToBase64String(RandomNumberGenerator.GetBytes(24));
    foreach (var ch in new[] { "/", "+", "=" })
    {
        generated = generated.Replace(ch, "");
    }
    return generated;
}

static void WriteSecret(string secret)
{
    var settingsPath = Path.Combine(InstallDir, "appsettings.json");
    var json = JsonNode.Parse(File.ReadAllText(settingsPath))!;
    json["AGENT_SHARED_SECRET"] = secret;
    File.WriteAllText(settingsPath, json.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
}

static void CopyDirectory(string sourceDir, string destDir)
{
    Directory.CreateDirectory(destDir);
    foreach (var file in Directory.GetFiles(sourceDir))
    {
        File.Copy(file, Path.Combine(destDir, Path.GetFileName(file)), overwrite: true);
    }
    foreach (var dir in Directory.GetDirectories(sourceDir))
    {
        CopyDirectory(dir, Path.Combine(destDir, Path.GetFileName(dir)));
    }
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
