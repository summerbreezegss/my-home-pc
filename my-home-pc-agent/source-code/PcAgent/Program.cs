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
    if (!IsAuthorized(request))
    {
        return Results.Unauthorized();
    }
    System.Diagnostics.Process.Start("shutdown.exe", "/s /t 5");
    return Results.Json(new { result = "shutdown_scheduled", delay_seconds = 5 }, statusCode: StatusCodes.Status202Accepted);
});

app.MapPost("/restart", (HttpRequest request) =>
{
    if (!IsAuthorized(request))
    {
        return Results.Unauthorized();
    }
    System.Diagnostics.Process.Start("shutdown.exe", "/r /t 5");
    return Results.Json(new { result = "restart_scheduled", delay_seconds = 5 }, statusCode: StatusCodes.Status202Accepted);
});

app.Run();
