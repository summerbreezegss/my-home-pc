using System.Security.Cryptography;
using System.Text;

var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService();

if (string.IsNullOrEmpty(builder.Configuration["AGENT_SHARED_SECRET"]))
{
    throw new InvalidOperationException("AGENT_SHARED_SECRET is not configured in appsettings.json.");
}
var port = builder.Configuration["AGENT_PORT"] ?? "8585";

builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var app = builder.Build();

bool IsAuthorized(HttpRequest request)
{
    // Read fresh on every call (not captured once at startup) so editing AGENT_SHARED_SECRET
    // in appsettings.json takes effect via its built-in file-reload watcher, with no service restart needed.
    var sharedSecret = app.Configuration["AGENT_SHARED_SECRET"] ?? "";

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

app.MapGet("/health", () => Results.Ok(new { status = "ok", hostname = Environment.MachineName }));

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
