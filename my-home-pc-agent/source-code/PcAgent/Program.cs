using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;

// The value appsettings.json ships with. It's public knowledge (it's in the repo and in every
// installer copy), so treating it as a real secret would leave a never-configured install
// protected by a string anyone can look up.
const string PlaceholderSecret = "changeme-must-match-relay-env";

var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService();

var configuredSecret = builder.Configuration["AGENT_SHARED_SECRET"];
if (string.IsNullOrEmpty(configuredSecret) || configuredSecret == PlaceholderSecret)
{
    throw new InvalidOperationException(
        "AGENT_SHARED_SECRET is not configured in appsettings.json — set it to a real secret (the shipped placeholder is rejected).");
}
var port = builder.Configuration["AGENT_PORT"] ?? "8585";

builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var app = builder.Build();

// Throttle repeated bad-secret attempts per source IP. In-memory on purpose — a service
// restart forgetting the counters costs an attacker at most one fresh window. While locked
// out, even the correct secret is refused, so the lockout can't be used as a guessing oracle.
const int MaxAuthFailures = 5;
var authLockout = TimeSpan.FromMinutes(5);
var authFailures = new ConcurrentDictionary<string, (int Count, DateTime LastFailureUtc)>();

// Returns null when the request may proceed, otherwise the 401/429 to send back.
// Every rejection is logged with the source IP — as a Windows Service that lands in the
// Application event log, timestamped, which is the audit trail for secret-guessing.
IResult? GateAuth(HttpRequest request)
{
    var ip = request.HttpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown";
    var now = DateTime.UtcNow;

    if (authFailures.TryGetValue(ip, out var entry))
    {
        if (now - entry.LastFailureUtc > authLockout)
        {
            authFailures.TryRemove(ip, out _);
        }
        else if (entry.Count >= MaxAuthFailures)
        {
            app.Logger.LogWarning("Auth locked out: ip={Ip} path={Path}", ip, request.Path);
            return Results.StatusCode(StatusCodes.Status429TooManyRequests);
        }
    }

    if (!IsAuthorized(request))
    {
        var updated = authFailures.AddOrUpdate(
            ip,
            _ => (1, now),
            (_, e) => (now - e.LastFailureUtc > authLockout ? 1 : e.Count + 1, now));
        app.Logger.LogWarning("Auth rejected: ip={Ip} path={Path} consecutiveFailures={Count}",
            ip, request.Path, updated.Count);
        return Results.Unauthorized();
    }

    authFailures.TryRemove(ip, out _);
    return null;
}

bool IsAuthorized(HttpRequest request)
{
    // Read fresh on every call (not captured once at startup) so editing AGENT_SHARED_SECRET
    // in appsettings.json takes effect via its built-in file-reload watcher, with no service restart needed.
    var sharedSecret = app.Configuration["AGENT_SHARED_SECRET"] ?? "";

    // The startup check rejects these, but hot-reload means the file can change while running —
    // never authorize against an empty or publicly-known value.
    if (sharedSecret.Length == 0 || sharedSecret == PlaceholderSecret)
    {
        return false;
    }

    const string prefix = "Bearer ";
    var header = request.Headers.Authorization.ToString();
    if (!header.StartsWith(prefix, StringComparison.Ordinal))
    {
        return false;
    }

    var tokenBytes = Encoding.UTF8.GetBytes(header[prefix.Length..]);
    var secretBytes = Encoding.UTF8.GetBytes(sharedSecret);
    return tokenBytes.Length == secretBytes.Length
        && CryptographicOperations.FixedTimeEquals(tokenBytes, secretBytes);
}

// No hostname here: /health is unauthenticated, and a machine name is free reconnaissance
// for anything that can reach this port.
app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

app.MapPost("/shutdown", (HttpRequest request) =>
{
    if (GateAuth(request) is { } denied)
    {
        return denied;
    }
    System.Diagnostics.Process.Start("shutdown.exe", "/s /t 5");
    return Results.Json(new { result = "shutdown_scheduled", delay_seconds = 5 }, statusCode: StatusCodes.Status202Accepted);
});

app.MapPost("/restart", (HttpRequest request) =>
{
    if (GateAuth(request) is { } denied)
    {
        return denied;
    }
    System.Diagnostics.Process.Start("shutdown.exe", "/r /t 5");
    return Results.Json(new { result = "restart_scheduled", delay_seconds = 5 }, statusCode: StatusCodes.Status202Accepted);
});

app.Run();
